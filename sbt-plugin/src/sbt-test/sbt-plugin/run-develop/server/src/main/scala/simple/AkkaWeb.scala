package simple

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import com.typesafe.config.ConfigFactory

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration

import com.dispalt.pop.Base

class AkkaWeb extends Base {
  var sys: ActorSystem                          = _
  var bindingFuture: Future[Http.ServerBinding] = _

  def start(cl: ClassLoader, port: Int) = {
    val config          = ConfigFactory.load(cl)
    implicit val system = ActorSystem("my-system", config, cl)
    sys = system
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    println("Foo1")

    val route =
      path("hello") {
        get {
          complete(
            HttpEntity(
              ContentTypes.`text/html(UTF-8)`,
              s"""
                 |<!DOCTYPE html>
                 |<html lang="en">
                 |<head>
                 |  <meta charset="UTF-8">
                 |</head>
                 |<body>
                 |  <div id="root">
                 |  </div>
                 |  <div id="scalajsShoutOut">
                 |  </div>
                 |  <script src="/assets/client-fastopt-bundle.js"></script>
                 |</body>
                 |</html>
          """.stripMargin
            )
          )
        }
      } ~
        pathPrefix("assets" / Remaining) { file =>
          // optionally compresses the response with Gzip or Deflate
          // if the client accepts compressed responses
          encodeResponse {
            getFromResource("public/" + file)
          }
        }

    bindingFuture = Http().bindAndHandle(route, "localhost", port)
  }

  def stop() = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val r = for {
      i <- bindingFuture
      _ <- i.unbind()
      t <- sys.terminate()
    } yield {}
    Await.result(r, Duration.Inf)
  }

  def restart() = println("restart")
}
