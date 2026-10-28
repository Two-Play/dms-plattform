package dms.search.core

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import zio.*

import java.time.Duration as JDuration

/** A single-node OpenSearch for tests, the same image as compose.yaml, security plugin disabled. Also used by
  * the application tests.
  */
object OpenSearchContainer:

  /** Same image as compose.yaml. */
  val image =
    "opensearchproject/opensearch:3.8.0@sha256:fafe3fc3587088674669235575aa166228c48bdb940294a8cdbbc1da75236a40"

  val container: ZLayer[Any, Throwable, GenericContainer] = ZLayer.scoped(
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val c = GenericContainer(
        dockerImage = image,
        exposedPorts = Seq(9200),
        env = Map(
          "discovery.type"              -> "single-node",
          "DISABLE_SECURITY_PLUGIN"     -> "true",
          "DISABLE_INSTALL_DEMO_CONFIG" -> "true",
          "OPENSEARCH_JAVA_OPTS"        -> "-Xms512m -Xmx512m"
        ),
        waitStrategy = Wait
          .forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s")
          .forPort(9200)
          .forStatusCode(200)
          .withStartupTimeout(JDuration.ofMinutes(3))
      )
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)
  )

  /** Configuration pointing to the container; `prefix` keeps the indices of different suites apart. */
  def config(prefix: String): ZLayer[GenericContainer, Nothing, OpenSearchConfig] =
    ZLayer.fromFunction((c: GenericContainer) =>
      OpenSearchConfig(s"http://${c.host}:${c.mappedPort(9200)}", indexPrefix = prefix, timeoutSeconds = 30)
    )

  def layer(prefix: String): ZLayer[Any, Throwable, OpenSearchConfig] = container >>> config(prefix)
