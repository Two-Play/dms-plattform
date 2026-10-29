import sbt.*

/** Central version management. Changes here affect all modules. */
object Versions {
  val scala         = "3.9.0"  // Scala 3 LTS, see docs/adr/0002
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
  val zioJson       = "1.1.1"  // same as tapir-json-zio
  val cel           = "0.15.0"
  val flowable      = "8.0.0"  // embedded workflow engine, see docs/adr/0006 and 0024
  val veraPdf       = "1.30.2" // PDF/A validation, same release as the CLI of spike DMS-23 (ADR 0008, 0025)
  val pdfbox        = "3.0.8"  // on-demand PDFs of file documents (stamp, merge); text extraction in tests
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

  /** JSON for metadata profiles stored as jsonb (ADR 0019). */
  val json = Seq("dev.zio" %% "zio-json" % Versions.zioJson)

  val magnum = Seq("com.augustnagro" %% "magnum" % Versions.magnum)

  /** Common Expression Language for template conditions and computed fields (ADR 0020). */
  val cel = Seq("dev.cel" % "cel" % Versions.cel)

  /** Embedded BPMN engine (ADR 0006, 0024). `commons-logging` (from Spring 7) duplicates the classes of
    * `jcl-over-slf4j`, which Flowable brings as well.
    */
  val flowable = Seq(
    ("org.flowable" % "flowable-engine" % Versions.flowable).exclude("commons-logging", "commons-logging")
  )

  val jwt = Seq("com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJose)

  /** RFC 3161 timestamping for the audit trail anchors (ADR 0017). */
  val timestamping = Seq("org.bouncycastle" % "bcpkix-jdk18on" % Versions.bouncyCastle)

  /** PDF/A validation of renditions in-process (ADR 0025); veraPDF's model for Jakarta XML Binding. */
  val pdfValidation = Seq("org.verapdf" % "validation-model-jakarta" % Versions.veraPdf)

  /** Text extraction from PDFs, to check the running header and footer of renditions in tests. */
  val pdfText = Seq("org.apache.pdfbox" % "pdfbox" % Versions.pdfbox % Test)

  /** Marking and merging the on-demand PDFs of file documents (DMS-115, ADR 0042). */
  val pdfCompose = Seq("org.apache.pdfbox" % "pdfbox" % Versions.pdfbox)

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
