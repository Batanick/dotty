import scala.quoted._
import scala.quoted.staging._

object Test {

  def foo[T: Type](init: Expr[T]) given QuoteContext: Expr[Unit] = '{
    var x = $init
    println(x)
  }

  def main(args: Array[String]): Unit = {
    given as Toolbox = Toolbox.make(getClass.getClassLoader)
    run(foo('{Option(9)}))
  }

}
