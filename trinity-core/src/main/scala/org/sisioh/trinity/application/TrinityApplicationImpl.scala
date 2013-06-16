package org.sisioh.trinity.application

import com.twitter.conversions.storage._
import com.twitter.finagle.builder.{Server, ServerBuilder}
import com.twitter.finagle.http._
import com.twitter.finagle.http.{Request => FinagleRequest, Response => FinagleResponse}
import com.twitter.finagle.tracing.{Tracer, NullTracer}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.ostrich.admin.AdminServiceFactory
import com.twitter.ostrich.admin.JsonStatsLoggerFactory
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.twitter.ostrich.admin.ServiceTracker
import com.twitter.ostrich.admin.StatsFactory
import com.twitter.ostrich.admin.TimeSeriesCollectorFactory
import com.twitter.ostrich.admin.{Service => OstrichService}
import com.twitter.util.Await
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import org.sisioh.scala.toolbox.LoggingEx
import org.sisioh.trinity.domain._
import org.sisioh.trinity.infrastructure.DurationUtil
import org.sisioh.trinity.{ControllerService, Controller, Controllers}
import com.twitter.ostrich.admin.TimeSeriesCollectorFactory
import com.twitter.ostrich.admin.AdminServiceFactory
import com.twitter.ostrich.admin.JsonStatsLoggerFactory
import scala.Some
import com.twitter.finagle.http.RichHttp
import com.twitter.ostrich.admin.StatsFactory


class TrinityApplicationImpl(val config: Config, globalSetting: Option[GlobalSetting] = None)
  extends TrinityApplication with LoggingEx with OstrichService {

  private var server: Server = _

  val routeRepository = new RouteRepositoryOnMemory

  private val controllers = new Controllers
  private var filters: Seq[SimpleFilter[FinagleRequest, FinagleResponse]] = Seq.empty

  val pid = ManagementFactory.getRuntimeMXBean.getName.split('@').head


  def addRoute(route: Route):Unit = {
    routeRepository.store(route)
  }

  def getRoute(routeId: RouteId) = routeRepository.resolve(routeId)


  def allFilters(baseService: Service[FinagleRequest, FinagleResponse]) = {
    filters.foldRight(baseService) {
      (b, a) =>
        b andThen a
    }
  }

  /**
   * コントローラを追加する。
   *
   * @param controller [[org.sisioh.trinity.Controller]]
   */
  def registerController(controller: Controller) {
    controllers.add(controller)
  }

  def registerFilter(filter: SimpleFilter[FinagleRequest, FinagleResponse]) {
    filters = filters ++ Seq(filter)
  }

  private def initAdminService(runtimeEnv: RuntimeEnvironment) {
    AdminServiceFactory(
      httpPort = config.statsPort.get,
      statsNodes = StatsFactory(
        reporters = JsonStatsLoggerFactory(serviceName = Some("trinity")) ::
          TimeSeriesCollectorFactory() :: Nil
      ) :: Nil
    )(runtimeEnv)
  }


  def shutdown() {
    Await.ready(server.close())
    info("shutting down")
    System.exit(0)
  }

  def start() {
    start(NullTracer, new RuntimeEnvironment(this))
  }

  def start(tracer: Tracer = NullTracer, runtimeEnv: RuntimeEnvironment = new RuntimeEnvironment(this)) {

    ServiceTracker.register(this)

    if (config.statsEnabled) {
      initAdminService(runtimeEnv)
    }

    val controllerService = new ControllerService(controllers, globalSetting)
    val fileService = new FileService(config)

    registerFilter(fileService)

    val port = config.applicationPort.get

    val service: Service[FinagleRequest, FinagleResponse] = allFilters(controllerService)

    val http = {
      val result = Http()
      config.maxRequestSize.foreach {
        v =>
          result.maxRequestSize(v.megabytes)
      }
      config.maxResponseSize.foreach {
        v =>
          result.maxResponseSize(v.megabytes)
      }
      result
    }

    val codec = new RichHttp[FinagleRequest](http)

    val serverBuilder = ServerBuilder()
      .codec(codec)
      .bindTo(new InetSocketAddress(port))
      .tracer(tracer)
      .name(config.applicationName)

    config.maxConcurrentRequests.foreach {
      v =>
        serverBuilder.maxConcurrentRequests(v)
    }
    config.hostConnectionMaxIdleTime.foreach {
      v =>
        import DurationUtil._
        serverBuilder.hostConnectionMaxIdleTime(v.toTwitter)
    }


    server = serverBuilder
      .build(service)

    logger.info("process %s started on %s", pid, port)

    println("trinity process " + pid + " started on port: " + port.toString)
    println("config args:")
    println(config)

  }
}

