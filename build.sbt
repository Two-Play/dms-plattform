// DMS platform – multi-module build.
// Rule (docs/adr/0001): modules depend only on `kernel` and on the `*-api` of other modules,
// never on another module's `*-core`. Only `app` wires the core modules together.

ThisBuild / organization := "de.itheimnet.dms"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := Versions.scala
// Compiler options come from sbt-tpolecat; with SBT_TPOLECAT_CI=1 (CI) warnings become errors.

lazy val commonSettings = Seq(
  libraryDependencies ++= Dependencies.zio ++ Dependencies.zioTest,
  Test / fork := true
)

def module(id: String, dir: String): Project =
  Project(id, file(dir)).settings(commonSettings)

/** Business module with a public interface (`api`) and an implementation (`core`). */
def apiModule(name: String): Project  = module(s"$name-api", s"modules/$name/api").dependsOn(kernel)
def coreModule(name: String): Project =
  module(s"$name-core", s"modules/$name/core").dependsOn(kernel, db, storage)

lazy val kernel = module("kernel", "modules/kernel")

/** Shared database infrastructure: transactions, tenant context, migrations (ADR 0012, 0014). */
lazy val db = module("db", "modules/db")
  .dependsOn(kernel)
  .settings(
    libraryDependencies ++= Dependencies.database ++ Dependencies.magnum ++ Dependencies.integrationTest ++
      Dependencies.logging.map(_ % Test)
  )

/** Shared object storage infrastructure: S3 API with Object Lock (ADR 0009, 0016). */
lazy val storage = module("storage", "modules/storage")
  .dependsOn(kernel)
  .settings(
    libraryDependencies ++= Dependencies.objectStorage ++ Dependencies.objectStorageTest ++
      Dependencies.logging.map(_ % Test)
  )

lazy val identity = module("identity", "modules/identity")
  .dependsOn(kernel, db)
  .settings(libraryDependencies ++= Dependencies.jwt ++ Dependencies.json)

lazy val authz = module("authz", "modules/authz")
  .dependsOn(kernel, db, identity, documentsApi)
  .settings(libraryDependencies ++= Dependencies.integrationTest ++ Dependencies.logging.map(_ % Test))

lazy val templatesApi      = apiModule("templates")
lazy val templatesCore     = templatesCoreModule.dependsOn(templatesApi, documentsApi, auditApi)
lazy val documentsApi      = apiModule("documents").dependsOn(templatesApi)
lazy val documentsCore     = documentsCoreModule.dependsOn(documentsApi, templatesApi, auditApi)
lazy val workflowApi       = apiModule("workflow")
lazy val workflowCore      = workflowCoreModule.dependsOn(workflowApi, documentsApi, auditApi)
lazy val renderingApi      = apiModule("rendering")
lazy val renderingCore     = renderingCoreModule.dependsOn(renderingApi, documentsApi, auditApi)
lazy val archiveApi        = apiModule("archive").dependsOn(db)
lazy val archiveCore       = archiveCoreModule.dependsOn(archiveApi, documentsApi, auditApi)
lazy val searchApi         = apiModule("search").dependsOn(documentsApi)
lazy val searchCore        = searchCoreModule.dependsOn(searchApi, documentsApi, authz)
lazy val auditApi          = apiModule("audit").dependsOn(db)
lazy val auditCore         = coreModule("audit").dependsOn(auditApi).settings(auditCoreSettings)
lazy val integrationApi    = apiModule("integration")
lazy val notificationsApi  = apiModule("notifications")
lazy val notificationsCore = coreModule("notifications").dependsOn(notificationsApi)

/** Document classes and metadata profiles (ADR 0019): JSON columns; integration tests against PostgreSQL. */
def documentsCoreModule: Project = coreModule("documents").settings(
  libraryDependencies ++= Dependencies.json ++ Dependencies.integrationTest ++
    Dependencies.logging.map(_ % Test)
)

/** Full-text search (ADR 0009, 0029): OpenSearch over HTTP with zio-json; tests start OpenSearch and
  * PostgreSQL with Testcontainers.
  */
def searchCoreModule: Project = coreModule("search").settings(
  libraryDependencies ++= Dependencies.json ++ Dependencies.integrationTest ++ Dependencies.objectStorageTest ++
    Dependencies.logging.map(_ % Test),
  // Not logback-test.xml: the app tests depend on these test classes and have their own logback-test.xml.
  Test / javaOptions += "-Dlogback.configurationFile=logback-search-test.xml"
)

/** Legal holds (ADR 0031): tables with RLS and guards, the legal hold on rendition objects in object storage.
  * Tests start PostgreSQL and SeaweedFS with Testcontainers.
  */
def archiveCoreModule: Project = coreModule("archive").settings(
  libraryDependencies ++= Dependencies.json ++ Dependencies.integrationTest ++ Dependencies.objectStorageTest ++
    Dependencies.logging.map(_ % Test)
)

/** Approval workflows (ADR 0006, 0024): embedded Flowable; integration tests against PostgreSQL. */
def workflowCoreModule: Project = coreModule("workflow").settings(
  libraryDependencies ++= Dependencies.flowable ++ Dependencies.json ++ Dependencies.integrationTest ++
    Dependencies.logging.map(_ % Test)
)

/** Archive renditions (ADR 0008, 0025): HTML → PDF/A through the rendering worker, office files → PDF/A
  * through the conversion worker (ADR 0042), validation with veraPDF. Tests start the worker images from
  * `workers/rendering` and `workers/conversion` and SeaweedFS with Testcontainers.
  */
def renderingCoreModule: Project = coreModule("rendering").settings(
  libraryDependencies ++= Dependencies.json ++ Dependencies.pdfValidation ++ Dependencies.integrationTest ++
    Dependencies.objectStorageTest ++ Dependencies.pdfText ++ Dependencies.logging.map(_ % Test),
  Test / javaOptions ++= Seq(
    renderingWorkerDirectory.value,
    conversionWorkerDirectory.value,
    // Not logback-test.xml: the app tests depend on these test classes and have their own logback-test.xml.
    "-Dlogback.configurationFile=logback-rendering-test.xml"
  )
)

/** Where tests find the Dockerfile of the rendering worker (ADR 0025). */
lazy val renderingWorkerDirectory = Def.setting(
  s"-Ddms.rendering.workerDirectory=${((ThisBuild / baseDirectory).value / "workers" / "rendering").getAbsolutePath}"
)

/** Where tests find the Dockerfile of the conversion worker (ADR 0042). */
lazy val conversionWorkerDirectory = Def.setting(
  s"-Ddms.conversion.workerDirectory=${((ThisBuild / baseDirectory).value / "workers" / "conversion").getAbsolutePath}"
)

/** Templates (ADR 0007, 0020): CEL expressions, JSON content in jsonb; integration tests against PostgreSQL.
  */
def templatesCoreModule: Project = coreModule("templates").settings(
  libraryDependencies ++= Dependencies.cel ++ Dependencies.json ++ Dependencies.integrationTest ++
    Dependencies.logging.map(_ % Test)
)

/** Audit trail (ADR 0017): RFC 3161 timestamping; integration tests against PostgreSQL. */
lazy val auditCoreSettings = Seq(
  libraryDependencies ++= Dependencies.timestamping ++ Dependencies.integrationTest ++
    Dependencies.logging.map(_ % Test)
)

/** Background processing (ADR 0010) and adapters to business systems (ADR 0023); the ports are in
  * `integration-api`.
  */
lazy val integration = module("integration", "modules/integration")
  .dependsOn(kernel, db, documentsApi, integrationApi)
  .settings(
    libraryDependencies ++= Dependencies.json ++ Dependencies.integrationTest ++
      Dependencies.logging.map(_ % Test)
  )

lazy val app = module("app", "modules/app")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(
    db,
    storage,
    identity,
    authz,
    integration,
    templatesCore,
    documentsCore,
    workflowCore,
    // test->test: the rendering worker container and PDF helpers of the rendering tests
    renderingCore % "compile->compile;test->test",
    archiveCore,
    // test->test: the OpenSearch container of the search tests
    searchCore % "compile->compile;test->test",
    auditCore,
    notificationsCore
  )
  .settings(
    libraryDependencies ++= Dependencies.http ++ Dependencies.config ++ Dependencies.logging ++
      Dependencies.integrationTest ++ Dependencies.objectStorageTest ++ Dependencies.pdfCompose,
    Test / javaOptions ++= Seq(renderingWorkerDirectory.value, conversionWorkerDirectory.value),
    Compile / mainClass := Some("dms.app.Main"),
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "dms" / "app" / "AppBuildInfo.scala"
      val v    = version.value
      IO.write(
        file,
        s"""package dms.app
                        |
                        |object AppBuildInfo:
                        |  val version: String = "$v"
                        |""".stripMargin
      )
      Seq(file)
    }.taskValue,
    dockerBaseImage    := "eclipse-temurin:21-jre",
    dockerExposedPorts := Seq(8080)
  )

lazy val root = (project in file("."))
  .settings(name := "dms-plattform", publish / skip := true)
  .aggregate(
    kernel,
    db,
    storage,
    identity,
    authz,
    integration,
    integrationApi,
    app,
    templatesApi,
    templatesCore,
    documentsApi,
    documentsCore,
    workflowApi,
    workflowCore,
    renderingApi,
    renderingCore,
    archiveApi,
    archiveCore,
    searchApi,
    searchCore,
    auditApi,
    auditCore,
    notificationsApi,
    notificationsCore
  )
