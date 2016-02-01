package dotty.tools
package dotc
package core

import Contexts._, Types._, Symbols._, Names._, Flags._, Scopes._
import SymDenotations._, Denotations.SingleDenotation
import config.Printers._
import util.Positions._
import Decorators._
import StdNames._
import Annotations._
import util.SimpleMap
import collection.mutable
import ast.tpd._

trait TypeOps { this: Context => // TODO: Make standalone object.
  import TypeOps._

  /** The type `tp` as seen from prefix `pre` and owner `cls`. See the spec
   *  for what this means. Called very often, so the code is optimized heavily.
   *
   *  A tricky aspect is what to do with unstable prefixes. E.g. say we have a class
   *
   *    class C { type T; def f(x: T): T }
   *
   *  and an expression `e` of type `C`. Then computing the type of `e.f` leads
   *  to the query asSeenFrom(`C`, `(x: T)T`). What should its result be? The
   *  naive answer `(x: C#T)C#T` is incorrect given that we treat `C#T` as the existential
   *  `exists(c: C)c.T`. What we need to do instead is to skolemize the existential. So
   *  the answer would be `(x: c.T)c.T` for some (unknown) value `c` of type `C`.
   *  `c.T` is expressed in the compiler as a skolem type `Skolem(C)`.
   *
   *  Now, skolemization is messy and expensive, so we want to do it only if we absolutely
   *  must. Also, skolemizing immediately would mean that asSeenFrom was no longer
   *  idempotent - each call would return a type with a different skolem.
   *  Instead we produce an annotated type that marks the prefix as unsafe:
   *
   *     (x: (C @ UnsafeNonvariant)#T)C#T

   *  We also set a global state flag `unsafeNonvariant` to the current run.
   *  When typing a Select node, typer will check that flag, and if it
   *  points to the current run will scan the result type of the select for
   *  @UnsafeNonvariant annotations. If it finds any, it will introduce a skolem
   *  constant for the prefix and try again.
   *
   *  The scheme is efficient in particular because we expect that unsafe situations are rare;
   *  most compiles would contain none, so no scanning would be necessary.
   */
  final def asSeenFrom(tp: Type, pre: Type, cls: Symbol): Type =
    asSeenFrom(tp, pre, cls, null)

  /** Helper method, taking a map argument which is instantiated only for more
   *  complicated cases of asSeenFrom.
   */
  private def asSeenFrom(tp: Type, pre: Type, cls: Symbol, theMap: AsSeenFromMap): Type = {

    /** Map a `C.this` type to the right prefix. If the prefix is unstable and
     *  the `C.this` occurs in nonvariant or contravariant position, mark the map
     *  to be unstable.
     */
    def toPrefix(pre: Type, cls: Symbol, thiscls: ClassSymbol): Type = /*>|>*/ ctx.conditionalTraceIndented(TypeOps.track, s"toPrefix($pre, $cls, $thiscls)") /*<|<*/ {
      if ((pre eq NoType) || (pre eq NoPrefix) || (cls is PackageClass))
        tp
      else pre match {
        case pre: SuperType => toPrefix(pre.thistpe, cls, thiscls)
        case _ =>
          if (thiscls.derivesFrom(cls) && pre.baseTypeRef(thiscls).exists) {
            if (theMap != null && theMap.currentVariance <= 0 && !isLegalPrefix(pre)) {
              ctx.base.unsafeNonvariant = ctx.runId
              AnnotatedType(pre, Annotation(defn.UnsafeNonvariantAnnot, Nil))
            }
            else pre
          }
          else if ((pre.termSymbol is Package) && !(thiscls is Package))
            toPrefix(pre.select(nme.PACKAGE), cls, thiscls)
          else
            toPrefix(pre.baseTypeRef(cls).normalizedPrefix, cls.owner, thiscls)
      }
    }

    /*>|>*/ ctx.conditionalTraceIndented(TypeOps.track, s"asSeen ${tp.show} from (${pre.show}, ${cls.show})", show = true) /*<|<*/ { // !!! DEBUG
      tp match {
        case tp: NamedType =>
          val sym = tp.symbol
          if (sym.isStatic) tp
          else {
            val pre1 = asSeenFrom(tp.prefix, pre, cls, theMap)
            if (pre1.isUnsafeNonvariant)
              pre1.member(tp.name).info match {
                case TypeAlias(alias) =>
                  // try to follow aliases of this will avoid skolemization.
                  return alias
                case _ =>
              }
            tp.derivedSelect(pre1)
          }
        case tp: ThisType =>
          toPrefix(pre, cls, tp.cls)
        case _: BoundType | NoPrefix =>
          tp
        case tp: RefinedType =>
          tp.derivedRefinedType(
            asSeenFrom(tp.parent, pre, cls, theMap),
            tp.refinedName,
            asSeenFrom(tp.refinedInfo, pre, cls, theMap))
        case tp: TypeAlias if tp.variance == 1 => // if variance != 1, need to do the variance calculation
          tp.derivedTypeAlias(asSeenFrom(tp.alias, pre, cls, theMap))
        case _ =>
          (if (theMap != null) theMap else new AsSeenFromMap(pre, cls))
            .mapOver(tp)
      }
    }
  }

  private def isLegalPrefix(pre: Type)(implicit ctx: Context) =
    pre.isStable || !ctx.phase.isTyper

  /** The TypeMap handling the asSeenFrom in more complicated cases */
  class AsSeenFromMap(pre: Type, cls: Symbol) extends TypeMap {
    def apply(tp: Type) = asSeenFrom(tp, pre, cls, this)

    /** A method to export the current variance of the map */
    def currentVariance = variance
  }

  /** Approximate a type `tp` with a type that does not contain skolem types.
   */
  final def deskolemize(tp: Type): Type = deskolemize(tp, 1, Set())

  private def deskolemize(tp: Type, variance: Int, seen: Set[SkolemType]): Type = {
    def approx(lo: Type = defn.NothingType, hi: Type = defn.AnyType, newSeen: Set[SkolemType] = seen) =
      if (variance == 0) NoType
      else deskolemize(if (variance < 0) lo else hi, variance, newSeen)
    tp match {
      case tp: SkolemType =>
        if (seen contains tp) NoType
        else approx(hi = tp.info, newSeen = seen + tp)
      case tp: NamedType =>
        val sym = tp.symbol
        if (sym.isStatic) tp
        else {
          val pre1 = deskolemize(tp.prefix, variance, seen)
          if (pre1 eq tp.prefix) tp
          else {
            val d = tp.prefix.member(tp.name)
            d.info match {
              case TypeAlias(alias) => deskolemize(alias, variance, seen)
              case _ =>
                if (pre1.exists && !pre1.isRef(defn.NothingClass)) tp.derivedSelect(pre1)
                else {
                  ctx.log(s"deskolem: $tp: ${tp.info}")
                  tp.info match {
                    case TypeBounds(lo, hi) => approx(lo, hi)
                    case info => approx(defn.NothingType, info)
                  }
                }
            }
          }
        }
      case _: ThisType | _: BoundType | _: SuperType | NoType | NoPrefix =>
        tp
      case tp: RefinedType =>
        val parent1 = deskolemize(tp.parent, variance, seen)
        if (parent1.exists) {
          val refinedInfo1 = deskolemize(tp.refinedInfo, variance, seen)
          if (refinedInfo1.exists)
            tp.derivedRefinedType(parent1, tp.refinedName, refinedInfo1)
          else
            approx(hi = parent1)
        }
        else approx()
      case tp: TypeAlias =>
        val alias1 = deskolemize(tp.alias, variance * tp.variance, seen)
        if (alias1.exists) tp.derivedTypeAlias(alias1)
        else approx(hi = TypeBounds.empty)
      case tp: TypeBounds =>
        val lo1 = deskolemize(tp.lo, -variance, seen)
        val hi1 = deskolemize(tp.hi, variance, seen)
        if (lo1.exists && hi1.exists) tp.derivedTypeBounds(lo1, hi1)
        else approx(hi =
          if (lo1.exists) TypeBounds.lower(lo1)
          else if (hi1.exists) TypeBounds.upper(hi1)
          else TypeBounds.empty)
      case tp: ClassInfo =>
        val pre1 = deskolemize(tp.prefix, variance, seen)
        if (pre1.exists) tp.derivedClassInfo(pre1)
        else NoType
      case tp: AndOrType =>
        val tp1d = deskolemize(tp.tp1, variance, seen)
        val tp2d = deskolemize(tp.tp2, variance, seen)
        if (tp1d.exists && tp2d.exists)
          tp.derivedAndOrType(tp1d, tp2d)
        else if (tp.isAnd)
          approx(hi = tp1d & tp2d)  // if one of tp1d, tp2d exists, it is the result of tp1d & tp2d
        else
          approx(lo = tp1d & tp2d)
      case tp: WildcardType =>
        val bounds1 = deskolemize(tp.optBounds, variance, seen)
        if (bounds1.exists) tp.derivedWildcardType(bounds1)
        else WildcardType
      case _ =>
        if (tp.isInstanceOf[MethodicType]) assert(variance != 0, tp)
        deskolemizeMap.mapOver(tp, variance, seen)
    }
  }

  object deskolemizeMap extends TypeMap {
    private var seen: Set[SkolemType] = _
    def apply(tp: Type) = deskolemize(tp, variance, seen)
    def mapOver(tp: Type, variance: Int, seen: Set[SkolemType]) = {
      val savedVariance = this.variance
      val savedSeen = this.seen
      this.variance = variance
      this.seen = seen
      try super.mapOver(tp)
      finally {
        this.variance = savedVariance
        this.seen = savedSeen
      }
    }
  }

  /** Implementation of Types#simplified */
  final def simplify(tp: Type, theMap: SimplifyMap): Type = tp match {
    case tp: NamedType =>
      if (tp.symbol.isStatic) tp
      else tp.derivedSelect(simplify(tp.prefix, theMap)) match {
        case tp1: NamedType if tp1.denotationIsCurrent =>
          val tp2 = tp1.reduceProjection
          //if (tp2 ne tp1) println(i"simplified $tp1 -> $tp2")
          tp2
        case tp1 => tp1
      }
    case tp: PolyParam =>
      typerState.constraint.typeVarOfParam(tp) orElse tp
    case  _: ThisType | _: BoundType | NoPrefix =>
      tp
    case tp: RefinedType =>
      tp.derivedRefinedType(simplify(tp.parent, theMap), tp.refinedName, simplify(tp.refinedInfo, theMap))
    case tp: TypeAlias =>
      tp.derivedTypeAlias(simplify(tp.alias, theMap))
    case AndType(l, r) =>
      simplify(l, theMap) & simplify(r, theMap)
    case OrType(l, r) =>
      simplify(l, theMap) | simplify(r, theMap)
    case _ =>
      (if (theMap != null) theMap else new SimplifyMap).mapOver(tp)
  }

  class SimplifyMap extends TypeMap {
    def apply(tp: Type) = simplify(tp, this)
  }

  /** Approximate union type by intersection of its dominators.
   *  See Type#approximateUnion for an explanation.
   */
  def approximateUnion(tp: Type): Type = {
    /** a faster version of cs1 intersect cs2 */
    def intersect(cs1: List[ClassSymbol], cs2: List[ClassSymbol]): List[ClassSymbol] = {
      val cs2AsSet = new util.HashSet[ClassSymbol](100)
      cs2.foreach(cs2AsSet.addEntry)
      cs1.filter(cs2AsSet.contains)
    }
    /** The minimal set of classes in `cs` which derive all other classes in `cs` */
    def dominators(cs: List[ClassSymbol], accu: List[ClassSymbol]): List[ClassSymbol] = (cs: @unchecked) match {
      case c :: rest =>
        val accu1 = if (accu exists (_ derivesFrom c)) accu else c :: accu
        if (cs == c.baseClasses) accu1 else dominators(rest, accu1)
    }
    def approximateOr(tp1: Type, tp2: Type): Type = {
      def isClassRef(tp: Type): Boolean = tp match {
        case tp: TypeRef => tp.symbol.isClass
        case tp: RefinedType => isClassRef(tp.parent)
        case _ => false
      }
      def next(tp: TypeProxy) = tp.underlying match {
        case TypeBounds(_, hi) => hi
        case nx => nx
      }
      /** If `tp1` and `tp2` are typebounds, try to make one fit into the other
       *  or to make them equal, by instantiating uninstantiated type variables.
       */
      def homogenizedUnion(tp1: Type, tp2: Type): Type = {
        tp1 match {
          case tp1: TypeBounds =>
            tp2 match {
              case tp2: TypeBounds =>
                def fitInto(tp1: TypeBounds, tp2: TypeBounds): Unit = {
                  val nestedCtx = ctx.fresh.setNewTyperState
                  if (tp2.boundsInterval.contains(tp1.boundsInterval)(nestedCtx))
                    nestedCtx.typerState.commit()
                }
                fitInto(tp1, tp2)
                fitInto(tp2, tp1)
              case _ =>
            }
          case _ =>
        }
        tp1 | tp2
      }

      tp1 match {
        case tp1: RefinedType =>
          tp2 match {
            case tp2: RefinedType if tp1.refinedName == tp2.refinedName =>
              return tp1.derivedRefinedType(
                approximateUnion(OrType(tp1.parent, tp2.parent)),
                tp1.refinedName,
                homogenizedUnion(tp1.refinedInfo, tp2.refinedInfo).substRefinedThis(tp2, RefinedThis(tp1)))
                //.ensuring { x => println(i"approx or $tp1 | $tp2 = $x\n constr = ${ctx.typerState.constraint}"); true } // DEBUG
            case _ =>
          }
        case _ =>
      }
      tp1 match {
        case tp1: TypeProxy if !isClassRef(tp1) =>
          approximateUnion(next(tp1) | tp2)
        case _ =>
          tp2 match {
            case tp2: TypeProxy if !isClassRef(tp2) =>
              approximateUnion(tp1 | next(tp2))
            case _ =>
              val commonBaseClasses = tp.mapReduceOr(_.baseClasses)(intersect)
              val doms = dominators(commonBaseClasses, Nil)
              def baseTp(cls: ClassSymbol): Type =
                if (tp1.typeParams.nonEmpty) tp.baseTypeRef(cls)
                else tp.baseTypeWithArgs(cls)
              doms.map(baseTp).reduceLeft(AndType.apply)
          }
      }
    }
    if (ctx.featureEnabled(defn.LanguageModuleClass, nme.keepUnions)) tp
    else tp match {
      case tp: OrType =>
        approximateOr(tp.tp1, tp.tp2)
      case tp @ AndType(tp1, tp2) =>
        tp derived_& (approximateUnion(tp1), approximateUnion(tp2))
      case tp: RefinedType =>
        tp.derivedRefinedType(approximateUnion(tp.parent), tp.refinedName, tp.refinedInfo)
      case _ =>
        tp
    }
  }

  /** Is this type a path with some part that is initialized on use? */
  def isLateInitialized(tp: Type): Boolean = tp.dealias match {
    case tp: TermRef =>
      tp.symbol.isLateInitialized || isLateInitialized(tp.prefix)
    case _: SingletonType | NoPrefix =>
      false
    case tp: TypeRef =>
      true
    case tp: TypeProxy =>
      isLateInitialized(tp.underlying)
    case tp: AndOrType =>
      isLateInitialized(tp.tp1) || isLateInitialized(tp.tp2)
    case _ =>
      true
  }

  /** The realizability status of given type `tp`*/
  def realizability(tp: Type): Realizability = tp.dealias match {
    case tp: TermRef =>
      if (tp.symbol.isRealizable)
        if (tp.symbol.isLateInitialized || // we already checked realizability of info in that case
            !isLateInitialized(tp.prefix)) // symbol was definitely constructed in that case
          Realizable
        else
          realizability(tp.info)
      else if (!tp.symbol.isStable) NotStable
      else if (!tp.symbol.isEffectivelyFinal) new NotFinal(tp.symbol)
      else new ProblemInUnderlying(tp.info, realizability(tp.info))
    case _: SingletonType | NoPrefix =>
      Realizable
    case tp =>
      def isConcrete(tp: Type): Boolean = tp.dealias match {
        case tp: TypeRef => tp.symbol.isClass
        case tp: TypeProxy => isConcrete(tp.underlying)
        case tp: AndOrType => isConcrete(tp.tp1) && isConcrete(tp.tp2)
        case _ => false
      }
      if (!isConcrete(tp)) NotConcrete
      else boundsRealizability(tp)
  }

  /** `Realizable` if `tp` has good bounds, a `HasProblemBounds` instance
   *  pointing to a bad bounds member otherwise.
   */
  def boundsRealizability(tp: Type)(implicit ctx: Context) = {
    def hasBadBounds(mbr: SingleDenotation) = {
      val bounds = mbr.info.bounds
      !(bounds.lo <:< bounds.hi)
    }
    tp.nonClassTypeMembers.find(hasBadBounds) match {
      case Some(mbr) => new HasProblemBounds(mbr)
      case _ => Realizable
    }
  }

  /* Might need at some point in the future
  def memberRealizability(tp: Type)(implicit ctx: Context) = {
    println(i"check member rel of $tp")
    def isUnrealizable(fld: SingleDenotation) =
      !fld.symbol.is(Lazy) && realizability(fld.info) != Realizable
    tp.fields.find(isUnrealizable) match {
      case Some(fld) => new HasProblemField(fld)
      case _ => Realizable
    }
  }
  */

  private def enterArgBinding(formal: Symbol, info: Type, cls: ClassSymbol, decls: Scope) = {
    val lazyInfo = new LazyType { // needed so we do not force `formal`.
      def complete(denot: SymDenotation)(implicit ctx: Context): Unit = {
        denot setFlag formal.flags & RetainedTypeArgFlags
        denot.info = info
      }
    }
    val sym = ctx.newSymbol(
      cls, formal.name,
      formal.flagsUNSAFE & RetainedTypeArgFlags | BaseTypeArg | Override,
      lazyInfo,
      coord = cls.coord)
    cls.enter(sym, decls)
  }

  /** If `tpe` is of the form `p.x` where `p` refers to a package
   *  but `x` is not owned by a package, expand it to
   *
   *      p.package.x
   */
  def makePackageObjPrefixExplicit(tpe: NamedType): Type = {
    def tryInsert(pkgClass: SymDenotation): Type = pkgClass match {
      case pkgCls: PackageClassDenotation if !(tpe.symbol.maybeOwner is Package) =>
        tpe.derivedSelect(pkgCls.packageObj.valRef)
      case _ =>
        tpe
    }
    tpe.prefix match {
      case pre: ThisType if pre.cls is Package => tryInsert(pre.cls)
      case pre: TermRef if pre.symbol is Package => tryInsert(pre.symbol.moduleClass)
      case _ => tpe
    }
  }

  /** If we have member definitions
   *
   *     type argSym v= from
   *     type from v= to
   *
   *  where the variances of both alias are the same, then enter a new definition
   *
   *     type argSym v= to
   *
   *  unless a definition for `argSym` already exists in the current scope.
   */
  def forwardRef(argSym: Symbol, from: Symbol, to: TypeBounds, cls: ClassSymbol, decls: Scope) =
    argSym.info match {
      case info @ TypeBounds(lo2 @ TypeRef(_: ThisType, name), hi2) =>
        if (name == from.name &&
            (lo2 eq hi2) &&
            info.variance == to.variance &&
            !decls.lookup(argSym.name).exists) {
              // println(s"short-circuit ${argSym.name} was: ${argSym.info}, now: $to")
              enterArgBinding(argSym, to, cls, decls)
            }
      case _ =>
    }


  /** Normalize a list of parent types of class `cls` that may contain refinements
   *  to a list of typerefs referring to classes, by converting all refinements to member
   *  definitions in scope `decls`. Can add members to `decls` as a side-effect.
   */
  def normalizeToClassRefs(parents: List[Type], cls: ClassSymbol, decls: Scope): List[TypeRef] = {

    /** If we just entered the type argument binding
     *
     *    type From = To
     *
     *  and there is a type argument binding in a parent in `prefs` of the form
     *
     *    type X = From
     *
     *  then also add the binding
     *
     *    type X = To
     *
     *  to the current scope, provided (1) variances of both aliases are the same, and
     *  (2) X is not yet defined in current scope. This "short-circuiting" prevents
     *  long chains of aliases which would have to be traversed in type comparers.
     */
    def forwardRefs(from: Symbol, to: Type, prefs: List[TypeRef]) = to match {
      case to @ TypeBounds(lo1, hi1) if lo1 eq hi1 =>
        for (pref <- prefs)
          for (argSym <- pref.decls)
            if (argSym is BaseTypeArg)
              forwardRef(argSym, from, to, cls, decls)
      case _ =>
    }

    // println(s"normalizing $parents of $cls in ${cls.owner}") // !!! DEBUG

    // A map consolidating all refinements arising from parent type parameters
    var refinements: SimpleMap[TypeName, Type] = SimpleMap.Empty

    // A map of all formal type parameters of base classes that get refined
    var formals: SimpleMap[TypeName, Symbol] = SimpleMap.Empty // A map of all formal parent parameter

    // Strip all refinements from parent type, populating `refinements` and `formals` maps.
    def normalizeToRef(tp: Type): TypeRef = tp.dealias match {
      case tp: TypeRef =>
        tp
      case tp @ RefinedType(tp1, name: TypeName) =>
        val prevInfo = refinements(name)
        refinements = refinements.updated(name,
            if (prevInfo == null) tp.refinedInfo else prevInfo & tp.refinedInfo)
        formals = formals.updated(name, tp1.typeParamNamed(name))
        normalizeToRef(tp1)
      case ErrorType =>
        defn.AnyType
      case AnnotatedType(tpe, _) =>
        normalizeToRef(tpe)
      case _ =>
        throw new TypeError(s"unexpected parent type: $tp")
    }
    val parentRefs = parents map normalizeToRef

    // Enter all refinements into current scope.
    refinements foreachBinding { (name, refinedInfo) =>
      assert(decls.lookup(name) == NoSymbol, // DEBUG
        s"redefinition of ${decls.lookup(name).debugString} in ${cls.showLocated}")
      enterArgBinding(formals(name), refinedInfo, cls, decls)
    }
    // Forward definitions in super classes that have one of the refined paramters
    // as aliases directly to the refined info.
    // Note that this cannot be fused bwith the previous loop because we now
    // assume that all arguments have been entered in `decls`.
    refinements foreachBinding { (name, refinedInfo) =>
      forwardRefs(formals(name), refinedInfo, parentRefs)
    }
    parentRefs
  }

  /** An argument bounds violation is a triple consisting of
   *   - the argument tree
   *   - a string "upper" or "lower" indicating which bound is violated
   *   - the violated bound
   */
  type BoundsViolation = (Tree, String, Type)

  /** The list of violations where arguments are not within bounds.
   *  @param  args          The arguments
   *  @param  boundss       The list of type bounds
   *  @param  instantiate   A function that maps a bound type and the list of argument types to a resulting type.
   *                        Needed to handle bounds that refer to other bounds.
   */
  def boundsViolations(args: List[Tree], boundss: List[TypeBounds], instantiate: (Type, List[Type]) => Type)(implicit ctx: Context): List[BoundsViolation] = {
    val argTypes = args.tpes
    val violations = new mutable.ListBuffer[BoundsViolation]
    for ((arg, bounds) <- args zip boundss) {
      def checkOverlapsBounds(lo: Type, hi: Type): Unit = {
        //println(i"instantiating ${bounds.hi} with $argTypes")
        //println(i" = ${instantiate(bounds.hi, argTypes)}")
        val hiBound = instantiate(bounds.hi, argTypes.mapConserve(_.bounds.hi))
        val loBound = instantiate(bounds.lo, argTypes.mapConserve(_.bounds.lo))
          // Note that argTypes can contain a TypeBounds type for arguments that are
          // not fully determined. In that case we need to check against the hi bound of the argument.
        if (!(lo <:< hiBound)) violations += ((arg, "upper", hiBound))
        if (!(loBound <:< hi)) violations += ((arg, "lower", bounds.lo))
      }
      arg.tpe match {
        case TypeBounds(lo, hi) => checkOverlapsBounds(lo, hi)
        case tp => checkOverlapsBounds(tp, tp)
      }
    }
    violations.toList
  }

  /** Is `feature` enabled in class `owner`?
   *  This is the case if one of the following two alternatives holds:
   *
   *  1. The feature is imported by a named import
   *
   *       import owner.feature
   *
   *  (the feature may be bunched with others, or renamed, but wildcard imports
   *  don't count).
   *
   *  2. The feature is enabled by a compiler option
   *
   *       - language:<prefix>feature
   *
   *  where <prefix> is the full name of the owner followed by a "." minus
   *  the prefix "dotty.language.".
   */
  def featureEnabled(owner: ClassSymbol, feature: TermName): Boolean = {
    def toPrefix(sym: Symbol): String =
      if (sym eq defn.LanguageModuleClass) "" else toPrefix(sym.owner) + sym.name + "."
    def featureName = toPrefix(owner) + feature
    def hasImport(implicit ctx: Context): Boolean = (
         ctx.importInfo != null
      && (   (ctx.importInfo.site.widen.typeSymbol eq owner)
          && ctx.importInfo.originals.contains(feature)
          ||
          { var c = ctx.outer
            while (c.importInfo eq ctx.importInfo) c = c.outer
            hasImport(c)
          }))
    def hasOption = ctx.base.settings.language.value exists (s => s == featureName || s == "_")
    hasImport || hasOption
  }

  /** Is auto-tupling enabled? */
  def canAutoTuple =
    !featureEnabled(defn.LanguageModuleClass, nme.noAutoTupling)

  def scala2Mode =
    featureEnabled(defn.LanguageModuleClass, nme.Scala2)

  def testScala2Mode(msg: String, pos: Position) = {
    if (scala2Mode) migrationWarning(msg, pos)
    scala2Mode
  }
}

object TypeOps {
  val emptyDNF = (Nil, Set[Name]()) :: Nil
  @sharable var track = false // !!!DEBUG

  // ----- Realizibility Status -----------------------------------------------------

  abstract class Realizability(val msg: String)

  object Realizable extends Realizability("")

  object NotConcrete extends Realizability(" is not a concrete type")

  object NotStable extends Realizability(" is not a stable reference")

  class NotFinal(sym: Symbol)(implicit ctx: Context)
  extends Realizability(i" refers to nonfinal $sym")

  class HasProblemBounds(typ: SingleDenotation)(implicit ctx: Context)
  extends Realizability(i" has a member $typ with possibly conflicting bounds ${typ.info.bounds.lo} <: ... <: ${typ.info.bounds.hi}")

  /* Might need at some point in the future
  class HasProblemField(fld: SingleDenotation)(implicit ctx: Context)
  extends Realizability(i" has a member $fld which is uneligible as a path since ${fld.symbol.name}${ctx.realizability(fld.info)}")
  */
  
  class ProblemInUnderlying(tp: Type, problem: Realizability)(implicit ctx: Context)
  extends Realizability(i"s underlying type ${tp}${problem.msg}")
}
