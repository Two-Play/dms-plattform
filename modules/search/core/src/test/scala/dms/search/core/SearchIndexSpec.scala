package dms.search.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.authz.*
import dms.db.{Db, DbConfig, Migrations}
import dms.documents.DocumentStatus
import dms.documents.metadata.MetadataValue
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.search.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.sql.DataSource

/** The search index against OpenSearch and PostgreSQL in Testcontainers (DMS-51, DMS-52): indexing, full-text
  * search with German and English stemming, snippets, facets, the permission filter compared with the policy,
  * ordering of concurrent writes, synchronization and rebuild with an alias move.
  */
object SearchIndexSpec extends ZIOSpecDefault:
  import AccessGen.{otherTenant, tenant}

  /** Documents by tenant, the source of truth of these tests. */
  final class TestSource(state: Ref[Map[DocumentId, List[SearchDocument]]]) extends SearchSource:
    def changedDocuments(since: Option[Instant]): IO[SearchError, List[DocumentId]] =
      state.get.map(
        _.collect { case (id, docs) if since.forall(s => docs.exists(!_.changedAt.isBefore(s))) => id }.toList
          .sortBy(_.value)
      )
    def load(t: TenantId, id: DocumentId): IO[SearchError, List[SearchDocument]] =
      state.get.map(_.getOrElse(id, Nil))

    def put(docs: SearchDocument*): UIO[Unit] =
      state.update(m =>
        docs.groupBy(_.documentId).foldLeft(m)((acc, entry) => acc.updated(entry._1, entry._2.toList))
      )
    def remove(id: DocumentId): UIO[Unit] = state.update(_ - id)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val database: ZLayer[Any, Throwable, Db] = ZLayer.scoped(
    for
      c <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _     <- owner
        .transactSystem(Db.sql {
          List(tenant, otherTenant).foreach(t =>
            sql"insert into dms.tenant (id, name) values (${t.value}, ${t.value.toString})".update.run()
          )
        })
        .orDieWith(e => RuntimeException(e.toString))
      app <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 4)).map(Db(_))
    yield app
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private type Env = SearchIndex & TestSource & OpenSearch & OpenSearchConfig & Db

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      OpenSearchContainer.layer("dms-test"),
      OpenSearch.layer,
      ZLayer.fromZIO(Ref.make(Map.empty[DocumentId, List[SearchDocument]]).map(TestSource(_))),
      ZLayer.succeed(SearchSettings(batchSize = 7)),
      SearchIndex.layer
    )

  private val t0 = Instant.parse("2026-10-01T08:00:00Z")

  private def doc(
      title: String,
      text: String,
      classPath: List[String] = List("contract"),
      status: DocumentStatus = DocumentStatus.Published,
      unit: Option[String] = Some("Legal"),
      confidentiality: Confidentiality = Confidentiality.Internal,
      language: String = "de",
      author: UserId = UserId("author"),
      document: DocumentId = DocumentId.random(),
      version: UUID = UUID.randomUUID(),
      label: String = "1.0",
      changedAt: Instant = t0,
      t: TenantId = tenant,
      allowed: Set[String] = Set.empty
  ): SearchDocument =
    SearchDocument(
      t,
      document,
      version,
      label,
      title,
      text,
      classPath,
      status,
      Some(LocalDate.of(2026, 1, 1)),
      None,
      Some(t0),
      Nil,
      Some(language),
      Map(
        "monthlyFee" -> MetadataValue.Amount(BigDecimal(4800), "EUR"),
        "services"   -> MetadataValue.Many(List(MetadataValue.Text("Hosting"), MetadataValue.Text("Backup"))),
        "startDate"  -> MetadataValue.Date(LocalDate.of(2026, 1, 1)),
        "renewal"    -> MetadataValue.YesNo(true)
      ),
      AccessAttributes(unit, confidentiality.toString, author, None, allowed),
      changedAt
    )

  private def subject(user: String, assignments: RoleAssignment*): Subject =
    Subject(UserId(user), tenant, assignments.toSet)

  private val reader = subject("reader", RoleAssignment(Role.Reader))

  private def search(s: Subject, query: SearchQuery) =
    ZIO.serviceWithZIO[SearchIndex](_.search(s, query))

  private def put(docs: SearchDocument*) =
    for
      source <- ZIO.service[TestSource]
      _      <- source.put(docs*)
      n <- ZIO.serviceWithZIO[SearchIndex](_.indexDocuments(tenant, docs.map(_.documentId).distinct.toList))
    yield n

  /** Removes all entries, so tests do not see each other's documents. */
  private val clear =
    for
      index  <- ZIO.service[SearchIndex]
      client <- ZIO.service[OpenSearch]
      _      <- index.ensureIndex(tenant)
      _      <- client.deleteByQuery(index.alias(tenant), Filter.All.toJson)
      source <- ZIO.service[TestSource]
      ids    <- source.changedDocuments(None)
      _      <- ZIO.foreachDiscard(ids)(source.remove)
    yield ()

  def spec = suite("Search index")(
    test("finds versions by stemmed German and English words, with a plain-text snippet") {
      for
        _ <- clear
        _ <- put(
          doc(
            "Dienstleistungsvertrag Nordlicht",
            "Die Verträge regeln <b>Leistungen</b> & Preise für Kunden."
          ),
          doc("Marker", "Ein <mark>falscher</mark> Treffer im Text über Kündigung."),
          doc("Service contract", "The contracts govern hosting services.", language = "en")
        )
        german  <- search(reader, SearchQuery(Some("Vertrag")))
        english <- search(reader, SearchQuery(Some("contract")))
        html    <- search(reader, SearchQuery(Some("Leistungen")))
        none    <- search(reader, SearchQuery(Some("Ausschreibung")))
        marker  <- search(reader, SearchQuery(Some("Kündigung")))
      yield assertTrue(
        german.total == 1L,
        german.hits.head.title == "Dienstleistungsvertrag Nordlicht",
        german.hits.head.snippet.contains("<mark>Verträge</mark>"),
        english.hits.map(_.title) == List("Service contract"),
        english.hits.head.snippet.contains("<mark>contracts</mark>"),
        html.hits.head.snippet ==
          "Die Verträge regeln <b><mark>Leistungen</mark></b> & Preise für Kunden.",
        none.total == 0L,
        marker.hits.head.snippet == "Ein falscher Treffer im Text über <mark>Kündigung</mark>."
      )
    },
    test("finds a version by its exact document number (DMS-113)") {
      val numbered = doc("Travel policy", "Rules for business trips.", List("policy"))
      for
        _ <- clear
        _ <- put(
          numbered.copy(metadata =
            numbered.metadata + ("documentNumber" -> MetadataValue.Text("RL-2026-0042"))
          ),
          doc("Other policy", "Rules for something else.", List("policy"))
        )
        exact   <- search(reader, SearchQuery(Some("RL-2026-0042")))
        partial <- search(reader, SearchQuery(Some("RL-2026-0043")))
      yield assertTrue(
        exact.hits.map(_.title) == List("Travel policy"),
        partial.total == 0L
      )
    },
    test("an empty query lists by filter, with facets over class path, status and language") {
      for
        _        <- clear
        document <- ZIO.succeed(DocumentId.random())
        _        <- put(
          doc("A NDA", "Text a", List("contract", "nda"), language = "en"),
          doc("B Service", "Text b", List("contract", "service-contract")),
          doc("C Policy", "Text c", List("policy")),
          doc(
            "D old",
            "Text d",
            List("policy"),
            DocumentStatus.Superseded,
            document = document,
            label = "1.0"
          ),
          doc("D new", "Text d", List("policy"), DocumentStatus.ReviewDue, document = document, label = "2.0")
        )
        all        <- search(reader, SearchQuery(None))
        contracts  <- search(reader, SearchQuery(None, classes = List("contract")))
        superseded <- search(reader, SearchQuery(None, statuses = List(DocumentStatus.Superseded)))
        english    <- search(reader, SearchQuery(None, languages = List("en")))
        page2      <- search(reader, SearchQuery(None, page = 2, size = 3))
      yield assertTrue(
        all.total == 4L,
        all.hits.map(_.title) == List("A NDA", "B Service", "C Policy", "D new"),
        all.hits.head.snippet == "Text a",
        all.facets.classes.map(f => f.value -> f.count).toSet ==
          Set("contract" -> 2L, "nda" -> 1L, "service-contract" -> 1L, "policy" -> 2L),
        all.facets.statuses.map(f => f.value -> f.count).toSet ==
          Set("Published" -> 3L, "ReviewDue" -> 1L, "Superseded" -> 1L),
        all.facets.languages.map(f => f.value -> f.count).toSet == Set("de" -> 3L, "en" -> 1L),
        contracts.hits.map(_.title) == List("A NDA", "B Service"),
        contracts.facets.classes.map(_.value).toSet == Set("contract", "nda", "service-contract"),
        superseded.hits.map(h => (h.title, h.versionLabel, h.status)) ==
          List(("D old", "1.0", DocumentStatus.Superseded)),
        english.hits.map(_.title) == List("A NDA"),
        page2.hits.map(_.title) == List("D new"),
        page2.total == 4L
      )
    },
    test("hits, counts, facets and snippets only cover versions the caller may read") {
      for
        _ <- clear
        _ <- put(
          doc("Legal contract", "Secret merger terms", unit = Some("Legal")),
          doc("HR policy", "Secret salary bands", List("policy"), unit = Some("HR")),
          doc("Board memo", "Secret board decision", confidentiality = Confidentiality.StrictlyConfidential),
          doc(
            "Board minutes",
            "Secret minutes",
            confidentiality = Confidentiality.Confidential,
            allowed = Set("board")
          ),
          doc("Foreign", "Secret of another tenant", t = otherTenant)
        )
        legal <- search(
          subject("legal", RoleAssignment(Role.Reader, Some("Legal"))),
          SearchQuery(Some("secret"))
        )
        hr <- search(
          subject("hr", RoleAssignment(Role.Author, Some("HR"), Some("policy"))),
          SearchQuery(Some("secret"))
        )
        admin <- search(subject("admin", RoleAssignment(Role.TenantAdmin)), SearchQuery(Some("secret")))
        board <- search(
          Subject(UserId("b"), tenant, Set(RoleAssignment(Role.Reader)), Set("board")),
          SearchQuery(Some("secret"))
        )
        author  <- search(subject("author", RoleAssignment(Role.Reader)), SearchQuery(Some("secret")))
        expired <- search(
          subject("gone", RoleAssignment(Role.Reader, validUntil = Some(Instant.now().minusSeconds(1)))),
          SearchQuery(Some("secret"))
        )
      yield assertTrue(
        legal.total == 1L,
        legal.hits.map(_.title) == List("Legal contract"),
        legal.facets.classes.map(f => f.value -> f.count) == List("contract" -> 1L),
        legal.facets.statuses.map(f => f.value -> f.count) == List("Published" -> 1L),
        !legal.hits.exists(_.snippet.contains("salary")),
        hr.hits.map(_.title) == List("HR policy"),
        admin.total == 0L,
        admin.facets.classes.isEmpty,
        board.hits.map(_.title).toSet == Set("Legal contract", "HR policy", "Board minutes"),
        author.hits.map(_.title).toSet == Set("Legal contract", "HR policy", "Board memo", "Board minutes"),
        expired.total == 0L
      )
    },
    test("OpenSearch applies the permission filter exactly like the policy decides") {
      for
        _         <- clear
        resources <- Gen.listOfN(90)(AccessGen.resource).runHead.someOrFailException
        docs = resources.zipWithIndex.map((r, i) =>
          doc(
            s"Doc $i",
            "common text",
            r.documentClassPath,
            r.status.get,
            r.organizationalUnit,
            r.confidentiality,
            author = r.authorId.get,
            t = r.tenantId,
            allowed = r.allowedGroups
          ).copy(access = AccessFilter.attributes(r))
        )
        _        <- put(docs*)
        subjects <- Gen.listOfN(60)(AccessGen.subject).runHead.someOrFailException
        now      <- Clock.instant
        results  <- ZIO.foreach(subjects) { s =>
          // Validity of the generated assignments is relative to AccessGen.now; shift it to the real clock.
          val shift   = java.time.Duration.between(AccessGen.now, now)
          val shifted = s.copy(assignments =
            s.assignments.map(a =>
              a.copy(
                validFrom = shiftInstant(a.validFrom, shift),
                validUntil = a.validUntil.map(shiftInstant(_, shift))
              )
            )
          )
          search(shifted, SearchQuery(None, statuses = SearchDocument.IndexedStatuses.toList, size = 100))
            .map { result =>
              val expected = resources.zip(docs).collect {
                case (r, d) if Policy.decide(shifted, Action.ReadDocument, r, now).isPermitted => d.versionId
              }
              (result.hits.map(_.versionId).toSet, result.total, expected.toSet)
            }
        }
      yield assertTrue(
        results.forall((found, total, expected) => found == expected && total == expected.size.toLong),
        results.exists(_._3.nonEmpty),
        results.exists(_._3.isEmpty)
      )
    },
    test("an older state never replaces a newer one; versions that leave the index are removed") {
      for
        _        <- clear
        document <- ZIO.succeed(DocumentId.random())
        v1       <- ZIO.succeed(UUID.randomUUID())
        v2       <- ZIO.succeed(UUID.randomUUID())
        newer = doc(
          "Policy",
          "Version one",
          status = DocumentStatus.Superseded,
          document = document,
          version = v1,
          changedAt = t0.plusSeconds(60)
        )
        older = doc("Policy", "Version one", document = document, version = v1, changedAt = t0)
        _      <- put(newer)
        _      <- put(older)
        stored <- search(reader, SearchQuery(None, statuses = List(DocumentStatus.Superseded)))
        _      <- put(
          newer,
          doc(
            "Policy",
            "Version two",
            document = document,
            version = v2,
            label = "2.0",
            changedAt = t0.plusSeconds(60)
          )
        )
        both <- search(reader, SearchQuery(None, statuses = SearchDocument.IndexedStatuses.toList))
        _    <- put(
          doc(
            "Policy",
            "Version two",
            document = document,
            version = v2,
            label = "2.0",
            changedAt = t0.plusSeconds(90)
          )
        )
        one  <- search(reader, SearchQuery(None, statuses = SearchDocument.IndexedStatuses.toList))
        _    <- ZIO.serviceWithZIO[TestSource](_.remove(document))
        _    <- ZIO.serviceWithZIO[SearchIndex](_.indexDocument(tenant, document))
        gone <- search(reader, SearchQuery(None, statuses = SearchDocument.IndexedStatuses.toList))
      yield assertTrue(
        stored.hits.map(_.versionId) == List(v1),
        both.hits.map(_.versionId).toSet == Set(v1, v2),
        one.hits.map(_.versionId) == List(v2),
        gone.total == 0L
      )
    },
    test("synchronization indexes what changed since its previous run; a rebuild moves the alias") {
      for
        _      <- clear
        index  <- ZIO.service[SearchIndex]
        client <- ZIO.service[OpenSearch]
        source <- ZIO.service[TestSource]
        db     <- ZIO.service[Db]
        a = doc("Alpha", "first", changedAt = Instant.now().minusSeconds(3600))
        b = doc("Beta", "second", changedAt = Instant.now().minusSeconds(3600))
        _          <- source.put(a, b)
        first      <- index.sync(tenant)
        afterFirst <- search(reader, SearchQuery(None))
        c = doc("Gamma", "third", changedAt = Instant.now())
        _           <- source.put(c)
        second      <- index.sync(tenant)
        afterSecond <- search(reader, SearchQuery(None))
        before      <- client.aliasIndices(index.alias(tenant))
        rebuilt     <- index.rebuild(tenant)
        after       <- client.aliasIndices(index.alias(tenant))
        oldExists   <- ZIO.foreach(before)(i => client.send("HEAD", s"/$i").map(_._1))
        found       <- search(reader, SearchQuery(None))
        synced      <- db.transact(tenant)(
          Db.sql(sql"select count(*) from dms.search_sync_state".query[Long].run().head)
        )
      yield assertTrue(
        first == 2,
        afterFirst.hits.map(_.title) == List("Alpha", "Beta"),
        second == 1,
        afterSecond.hits.map(_.title) == List("Alpha", "Beta", "Gamma"),
        rebuilt.documents == 3,
        rebuilt.versions == 3,
        after == List(rebuilt.index),
        !before.contains(rebuilt.index),
        oldExists.forall(_ == 404),
        found.hits.map(_.title) == List("Alpha", "Beta", "Gamma"),
        synced == 1L
      )
    },
    test("invalid pages and statuses are rejected") {
      for
        page   <- search(reader, SearchQuery(None, page = 0)).flip
        size   <- search(reader, SearchQuery(None, size = 101)).flip
        window <- search(reader, SearchQuery(None, page = 101, size = 100)).flip
        status <- search(reader, SearchQuery(None, statuses = List(DocumentStatus.Draft))).flip
      yield assertTrue(
        List(page, size, window, status).forall {
          case SearchError.Invalid(_) => true
          case _                      => false
        }
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)

  private def shiftInstant(i: Instant, by: java.time.Duration): Instant =
    if i == Instant.EPOCH then i else i.plus(by)
