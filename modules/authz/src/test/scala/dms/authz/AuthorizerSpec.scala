package dms.authz

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.DocumentStatus
import dms.identity.Principal
import dms.kernel.{TenantId, UserId}
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.time.Instant
import javax.sql.DataSource

/** Role assignments and access groups in PostgreSQL (RLS, revocation, membership history) and denial logging,
  * against a Testcontainers database.
  */
object AuthorizerSpec extends ZIOSpecDefault:

  final case class Databases(app: Db, owner: Db)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val databases: ZLayer[Any, Throwable, Databases] = ZLayer.scoped(
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
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
    yield Databases(Db(app), Db(owner))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def newTenant(owner: Db): IO[DbError, TenantId] =
    val id = TenantId.random()
    owner
      .transactSystem(
        Db.sql(sql"insert into dms.tenant (id, name) values (${id.value}, ${"Tenant"})".update.run())
      )
      .as(id)

  private val admin = UserId("admin-1")
  private val alice = UserId("alice")

  def spec = suite("Authorizer")(
    test("granted assignments are loaded for the user and revocation ends them") {
      for
        dbs <- ZIO.service[Databases]
        t   <- newTenant(dbs.owner)
        id  <- dbs.app.transact(t)(
          RoleAssignments.Postgres.grant(
            alice,
            RoleAssignment(Role.Approver, documentClass = Some("contract")),
            admin
          )
        )
        loaded  <- dbs.app.transact(t)(RoleAssignments.Postgres.forUser(alice))
        revoked <- dbs.app.transact(t)(RoleAssignments.Postgres.revoke(id, Instant.now()))
        after   <- dbs.app.transact(t)(RoleAssignments.Postgres.forUser(alice))
      yield assertTrue(
        loaded.map(a => (a.role, a.documentClass)) == Set((Role.Approver, Some("contract"))),
        revoked,
        after.forall(a => !a.isActiveAt(Instant.now().plusSeconds(1)))
      )
    },
    test(
      "compliance officers can be assigned; all active assignments and memberships load at once (DMS-59)"
    ) {
      val bob   = UserId("bob")
      val carol = UserId("carol")
      val now   = Instant.now()
      for
        dbs <- ZIO.service[Databases]
        t   <- newTenant(dbs.owner)
        _   <- dbs.app.transact(t)(
          RoleAssignments.Postgres.grant(alice, RoleAssignment(Role.ComplianceOfficer, Some("hr")), admin) *>
            RoleAssignments.Postgres.grant(alice, RoleAssignment(Role.Reader), admin) *>
            RoleAssignments.Postgres.grant(bob, RoleAssignment(Role.Owner), admin) *>
            RoleAssignments.Postgres.grant(
              carol,
              RoleAssignment(
                Role.Reader,
                validFrom = now.minusSeconds(60),
                validUntil = Some(now.minusSeconds(1))
              ),
              admin
            ) *>
            Groups.Postgres.create("board", "Board", admin) *>
            Groups.Postgres.addMember("board", bob, admin)
        )
        active      <- dbs.app.transact(t)(RoleAssignments.Postgres.activeAt(now))
        memberships <- dbs.app.transact(t)(Groups.Postgres.currentMemberships)
      yield assertTrue(
        active.keySet == Set(alice, bob),
        active(alice).map(a => (a.role, a.organizationalUnit)) ==
          Set((Role.ComplianceOfficer, Some("hr")), (Role.Reader, None)),
        memberships == Map(bob -> Set("board"))
      )
    },
    test("assignments of another tenant are invisible") {
      for
        dbs <- ZIO.service[Databases]
        a   <- newTenant(dbs.owner)
        b   <- newTenant(dbs.owner)
        _   <- dbs.app.transact(a)(RoleAssignments.Postgres.grant(alice, RoleAssignment(Role.Owner), admin))
        inB <- dbs.app.transact(b)(RoleAssignments.Postgres.forUser(alice))
      yield assertTrue(inB.isEmpty)
    },
    test("the application cannot change the role of an assignment") {
      for
        dbs <- ZIO.service[Databases]
        t   <- newTenant(dbs.owner)
        _   <- dbs.app.transact(t)(RoleAssignments.Postgres.grant(alice, RoleAssignment(Role.Reader), admin))
        result <- dbs.app
          .transact(t)(Db.sql(sql"update dms.role_assignment set role = 'TenantAdmin'".update.run()))
          .either
      yield assertTrue(result.left.exists(_.isInstanceOf[DbError.PermissionDenied]))
    },
    test("a denied check fails with the reason and logs it") {
      for
        dbs <- ZIO.service[Databases]
        t   <- newTenant(dbs.owner)
        _   <- dbs.app.transact(t)(RoleAssignments.Postgres.grant(alice, RoleAssignment(Role.Reader), admin))
        authorizer = Authorizer(RoleAssignments.Postgres, Groups.Postgres)
        principal  = Principal(alice, t, Set.empty, None)
        subject <- dbs.app.transact(t)(authorizer.subject(principal))
        draft = Resource(t, status = Some(DocumentStatus.Draft), authorId = Some(admin))
        result <- authorizer.check(subject, Action.EditDraft, draft).either
        logs   <- ZTestLogger.logOutput
      yield assertTrue(
        result.left.exists(_.reason.contains("author or owner")),
        logs.exists(e =>
          e.message().startsWith("Access denied") && e.annotations.get("action").contains("EditDraft")
        )
      )
    },
    test("access groups: members join and leave, the subject carries the current groups (DMS-101)") {
      val bob = UserId("bob")
      val g   = Groups.Postgres
      for
        dbs   <- ZIO.service[Databases]
        t     <- newTenant(dbs.owner)
        other <- newTenant(dbs.owner)
        authorizer = Authorizer(RoleAssignments.Postgres, g)
        created   <- dbs.app.transact(t)(g.create("board", "Board", admin))
        again     <- dbs.app.transact(t)(g.create("board", "Board again", admin))
        _         <- dbs.app.transact(t)(g.create("hr", "HR", admin))
        added     <- dbs.app.transact(t)(g.addMember("board", alice, admin))
        twice     <- dbs.app.transact(t)(g.addMember("board", alice, admin))
        _         <- dbs.app.transact(t)(g.addMember("hr", bob, admin))
        subject   <- dbs.app.transact(t)(authorizer.subject(Principal(alice, t, Set("zitadel-role"), None)))
        known     <- dbs.app.transact(t)(g.existing(Set("board", "nope")))
        members   <- dbs.app.transact(t)(g.membersOfAny(Set("board", "hr")))
        removed   <- dbs.app.transact(t)(g.removeMember("board", alice, admin, Instant.now()))
        gone      <- dbs.app.transact(t)(g.removeMember("board", alice, admin, Instant.now()))
        after     <- dbs.app.transact(t)(authorizer.subject(Principal(alice, t, Set.empty, None)))
        history   <- dbs.app.transact(t)(g.members("board"))
        _         <- dbs.app.transact(t)(g.addMember("board", alice, admin))
        rejoined  <- dbs.app.transact(t)(g.members("board"))
        elsewhere <- dbs.app.transact(other)(g.list)
        rewrite   <- dbs.app
          .transact(t)(Db.sql(sql"update dms.access_group_member set user_id = 'mallory'".update.run()))
          .either
        delete <- dbs.app
          .transact(t)(Db.sql(sql"delete from dms.access_group_member".update.run()))
          .either
      yield assertTrue(
        created,
        !again,
        added,
        !twice,
        subject.groups == Set("board"),
        known == Set("board"),
        members == Set(alice, bob),
        removed,
        !gone,
        after.groups.isEmpty,
        history.map(m => (m.user, m.isCurrent)) == List((alice, false)),
        rejoined.map(m => (m.user, m.isCurrent)) == List((alice, true), (alice, false)),
        elsewhere.isEmpty,
        rewrite.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        delete.left.exists(_.isInstanceOf[DbError.PermissionDenied])
      )
    }
  ).provideShared(databases) @@ TestAspect.sequential @@ TestAspect.withLiveClock
