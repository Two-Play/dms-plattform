import sbt.*

/** Central version management. Changes here affect all modules. */
object Versions {
  val scala         = "3.9.0" // Scala 3 LTS, see docs/adr/0002
  val zio           = "2.1.27"
  val zioConfig     = "4.1.0"
  val zioLogging    = "2.5.3"
  val tapir         = "1.14.1"
  val logback       = "1.6.6"
  val postgres      = "42.7.14"
  val flyway        = "13.11.1"
  val hikari        = "7.1.0"
  val nimbusJose    = "10.10"
  val testcontainer = "0.44.1"
  val magnum        = "1.3.1"
  val bouncyCastle  = "1.86"
  val awsSdk        = "2.57.5"
}

object Dependencies {
  val zio = Seq(
    "dev.zio" %% "zio"         % Versions.zio,
    "dev.zio" %% "zio-streams" % Versions.zio
  )

  val zioTest = Seq(
    "dev.zio" %% "zio-test"          % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt"      % Versions.zio % Test,
    "dev.zio" %% "zio-test-magnolia" % Versions.zio % Test
  )

  val config = Seq(
    "dev.zio" %% "zio-config"          % Versions.zioConfig,
    "dev.zio" %% "zio-config-typesafe" % Versions.zioConfig,
    "dev.zio" %% "zio-config-magnolia" % Versions.zioConfig
  )

  val logging = Seq(
    "dev.zio"       %% "zio-logging"        % Versions.zioLogging,
    "dev.zio"       %% "zio-logging-slf4j2" % Versions.zioLogging,
    "ch.qos.logback" % "logback-classic"    % Versions.logback
  )

  val http = Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"   % Versions.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % Versions.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % Versions.tapir
  )

  val database = Seq(
    "org.postgresql" % "postgresql"                 % Versions.postgres,
    "org.flywaydb"   % "flyway-core"                % Versions.flyway,
    "org.flywaydb"   % "flyway-database-postgresql" % Versions.flyway,
    "com.zaxxer"     % "HikariCP"                   % Versions.hikari
  )

  val magnum = Seq("com.augustnagro" %% "magnum" % Versions.magnum)

  val jwt = Seq("com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJose)

  /** RFC 3161 timestamping for the audit trail anchors (ADR 0017). */
  val timestamping = Seq("org.bouncycastle" % "bcpkix-jdk18on" % Versions.bouncyCastle)

  val integrationTest = Seq(
    "com.dimafeng" %% "testcontainers-scala-postgresql" % Versions.testcontainer % Test
  )

  /** AWS SDK v2 S3 client with the JDK URL connection HTTP client (no Netty, no Apache HTTP). */
  val objectStorage = Seq(
    ("software.amazon.awssdk" % "s3" % Versions.awsSdk)
      .exclude("software.amazon.awssdk", "apache-client")
      .exclude("software.amazon.awssdk", "netty-nio-client"),
    "software.amazon.awssdk" % "url-connection-client" % Versions.awsSdk
  )

  val objectStorageTest = Seq(
    "com.dimafeng" %% "testcontainers-scala-core" % Versions.testcontainer % Test
  )
}
