package mesosphere.marathon.core.health.impl

import java.net.{ InetSocketAddress, Socket }
import java.security.cert.X509Certificate
import javax.net.ssl.{ KeyManager, SSLContext, X509TrustManager }

import akka.actor.{ Actor, ActorLogging, PoisonPill }
import akka.util.Timeout
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.health._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.util.ThreadPoolContext
import spray.client.pipelining._
import spray.http._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

import mesosphere.marathon.Protos

class HealthCheckWorkerActor extends Actor with ActorLogging {

  import HealthCheckWorker._

  implicit val system = context.system
  import context.dispatcher // execution context for futures

  def receive: Receive = {
    case HealthCheckJob(app, task, launched, check) =>
      val replyTo = sender() // avoids closing over the volatile sender ref

      doCheck(app, task, launched, check)
        .andThen {
          case Success(Some(result)) => replyTo ! result
          case Success(None) => // ignore
          case Failure(t) =>
            replyTo ! Unhealthy(
              task.taskId,
              launched.runSpecVersion,
              s"${t.getClass.getSimpleName}: ${t.getMessage}"
            )
        }
        .onComplete { case _ => self ! PoisonPill }
  }

  def doCheck(
    app: AppDefinition, task: Task, launched: Task.Launched, check: MarathonHealthCheck): Future[Option[HealthResult]] =
    task.effectiveIpAddress(app) match {
      case Some(host) =>
        val port = check.effectivePort(app, task)
        check match {
          case hc: MarathonHttpHealthCheck =>
            hc.protocol match {
              case Protos.HealthCheckDefinition.Protocol.HTTPS => https(task, launched, hc, host, port)
              case Protos.HealthCheckDefinition.Protocol.HTTP => http(task, launched, hc, host, port)
              case invalidProtocol: Protos.HealthCheckDefinition.Protocol =>
                Future.failed {
                  val message = s"Health check failed: HTTP health check contains invalid protocol: $invalidProtocol"
                  log.warning(message)
                  new UnsupportedOperationException(message)
                }
            }
          case hc: MarathonTcpHealthCheck => tcp(task, launched, hc, host, port)
        }
      case None =>
        Future.failed {
          val message = "Health check failed: unable to get the task's effective IP address"
          log.warning(message)
          new UnsupportedOperationException(message)
        }
    }

  def http(
    task: Task,
    launched: Task.Launched,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"http://$host:$port$absolutePath"
    log.debug("Checking the health of [{}] via HTTP", url)

    def get(url: String): Future[HttpResponse] = {
      implicit val requestTimeout = Timeout(check.timeout)
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
      pipeline(Get(url))
    }

    get(url).map { response =>
      if (acceptableResponses contains response.status.intValue)
        Some(Healthy(task.taskId, launched.runSpecVersion))
      else if (check.ignoreHttp1xx && (toIgnoreResponses contains response.status.intValue)) {
        log.debug(s"Ignoring health check HTTP response ${response.status.intValue} for ${task.taskId}")
        None
      } else {
        Some(Unhealthy(task.taskId, launched.runSpecVersion, response.status.toString()))
      }
    }
  }

  def tcp(
    task: Task,
    launched: Task.Launched,
    check: MarathonTcpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val address = s"$host:$port"
    val timeoutMillis = check.timeout.toMillis.toInt
    log.debug("Checking the health of [{}] via TCP", address)

    Future {
      val address = new InetSocketAddress(host, port)
      val socket = new Socket
      scala.concurrent.blocking {
        socket.connect(address, timeoutMillis)
        socket.close()
      }
      Some(Healthy(task.taskId, launched.runSpecVersion, Timestamp.now()))
    }(ThreadPoolContext.ioContext)
  }

  def https(
    task: Task,
    launched: Task.Launched,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"https://$host:$port$absolutePath"
    log.debug("Checking the health of [{}] via HTTPS", url)

    @SuppressWarnings(Array("NullParameter"))
    def get(url: String): Future[HttpResponse] = {
      implicit val requestTimeout = Timeout(check.timeout)
      implicit def trustfulSslContext: SSLContext = {
        object BlindFaithX509TrustManager extends X509TrustManager {
          @SuppressWarnings(Array("EmptyMethod"))
          def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
          @SuppressWarnings(Array("EmptyMethod"))
          def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
          def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
        }

        val context = SSLContext.getInstance("Default")
        context.init(Array[KeyManager](), Array(BlindFaithX509TrustManager), null)
        context
      }
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
      pipeline(Get(url))
    }

    get(url).map { response =>
      if (acceptableResponses contains response.status.intValue)
        Some(Healthy(task.taskId, launched.runSpecVersion))
      else
        Some(Unhealthy(task.taskId, launched.runSpecVersion, response.status.toString()))
    }
  }

}

object HealthCheckWorker {

  // Similar to AWS R53, we accept all responses in [200, 399]
  protected[health] val acceptableResponses = Range(200, 400)
  protected[health] val toIgnoreResponses = Range(100, 200)

  case class HealthCheckJob(app: AppDefinition, task: Task, launched: Task.Launched, check: MarathonHealthCheck)
}
