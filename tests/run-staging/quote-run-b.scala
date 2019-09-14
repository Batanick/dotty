
import scala.quoted._
import scala.quoted.staging._

object Test {
  def main(args: Array[String]): Unit = {
    given as Toolbox = Toolbox.make(getClass.getClassLoader)
    def lambdaExpr given QuoteContext = '{
      (x: Int) => println("lambda(" + x + ")")
    }
    println()

    val lambda = run(lambdaExpr)
    lambda(4)
    lambda(5)
  }
}
