import scala.concurrent.Future

Future(throw new Exception) onComplete {
  case _ => println("complete")
}