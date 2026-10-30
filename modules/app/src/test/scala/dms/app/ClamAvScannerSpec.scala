package dms.app

import com.dimafeng.testcontainers.GenericContainer
import dms.documents.{ScanError, ScanVerdict}
import org.testcontainers.containers.wait.strategy.Wait
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path}
import java.time.Duration as JDuration

/** The ClamAV adapter of the scan port (DMS-117, ADR 0041 addendum): `INSTREAM` against an in-process fake of
  * `clamd` (clean, infected, chunking, size limit, timeout, unreachable) and against the real `clamd` of the
  * pinned ClamAV image (Testcontainers) with the EICAR test file.
  */
object ClamAvScannerSpec extends ZIOSpecDefault:

  /** Same image as compose.yaml. */
  val clamavImage: String =
    "clamav/clamav:1.5.4@sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d"

  /** The EICAR anti-virus test file, assembled at runtime so that this source file is not flagged itself. */
  val eicar: String =
    List("X5O!P%@AP[4\\PZX54(P^)7CC)7}$", "EICAR-STANDARD-ANTIVIRUS", "-TEST-FILE!$H+H*").mkString

  private def file(content: Array[Byte]): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val path = Files.createTempFile("dms-scan-", ".bin")
      Files.write(path, content)
    })(path => ZIO.attemptBlocking(Files.deleteIfExists(path)).orDie)

  private def scan(scanner: ClamAvScanner, content: Array[Byte]) =
    ZIO.scoped(file(content).flatMap(path => scanner.scan(path, content.length.toLong).either))

  private def unavailable(result: Either[ScanError, ScanVerdict], text: String): Boolean =
    result match
      case Left(ScanError.Unavailable(message)) => message.contains(text)
      case _                                    => false

  private val fake = suite("against a fake clamd")(
    test("a clean file is Clean, a file with a finding is Infected with its signature") {
      ZIO.scoped(
        for
          clamd <- FakeClamd.make
          scanner = ClamAvScanner(ClamAvConfig("127.0.0.1", clamd.port, timeoutSeconds = 5))
          clean    <- scan(scanner, "Preisblatt 2027".getBytes(UTF_8))
          infected <- scan(scanner, s"Anlage ${FakeClamd.Marker}".getBytes(UTF_8))
          ping     <- scanner.ping.either
        yield assertTrue(
          clean == Right(ScanVerdict.Clean),
          infected == Right(ScanVerdict.Infected(FakeClamd.Signature)),
          ping.isRight,
          scanner.name == "clamav"
        )
      )
    },
    test("the file is streamed in length-prefixed chunks and arrives unchanged") {
      ZIO.scoped(
        for
          clamd <- FakeClamd.make
          scanner = ClamAvScanner(
            ClamAvConfig("127.0.0.1", clamd.port, timeoutSeconds = 5, chunkBytes = 1024)
          )
          content = Array.tabulate[Byte](10 * 1024 + 17)(i => (i % 251).toByte)
          result <- scan(scanner, content)
        yield assertTrue(result == Right(ScanVerdict.Clean), clamd.lastFile.get().sameElements(content))
      )
    },
    test("a refused size, a silent and a closing clamd fail closed as unavailable") {
      ZIO.scoped(
        for
          clamd <- FakeClamd.make
          scanner = ClamAvScanner(ClamAvConfig("127.0.0.1", clamd.port, timeoutSeconds = 1))
          content = "x".repeat(4096).getBytes(ISO_8859_1)
          _        <- ZIO.succeed(clamd.mode.set(FakeClamd.Mode.TooLarge))
          tooLarge <- scan(scanner, content)
          _        <- ZIO.succeed(clamd.mode.set(FakeClamd.Mode.Silent))
          silent   <- scan(scanner, content)
          _        <- ZIO.succeed(clamd.mode.set(FakeClamd.Mode.Close))
          closed   <- scan(scanner, content)
        yield assertTrue(
          unavailable(tooLarge, "StreamMaxLength"),
          unavailable(silent, "did not answer within 1 s"),
          unavailable(closed, "not reachable")
        )
      )
    },
    test("an unreachable clamd fails closed as unavailable") {
      for
        port <- ZIO.attemptBlocking {
          val socket = java.net.ServerSocket(0)
          try socket.getLocalPort
          finally socket.close()
        }
        scanner = ClamAvScanner(ClamAvConfig("127.0.0.1", port, timeoutSeconds = 2))
        result <- scan(scanner, "text".getBytes(UTF_8))
        ping   <- scanner.ping.either
      yield assertTrue(unavailable(result, s"127.0.0.1:$port is not reachable"), ping.isLeft)
    },
    test("replies of clamd are mapped to verdicts") {
      assertTrue(
        ClamAvScanner.verdictOf("stream: OK") == Right(ScanVerdict.Clean),
        ClamAvScanner.verdictOf("stream: Win.Test.EICAR_HDB-1 FOUND") ==
          Right(ScanVerdict.Infected("Win.Test.EICAR_HDB-1")),
        ClamAvScanner.verdictOf("INSTREAM size limit exceeded. ERROR").isLeft,
        ClamAvScanner.verdictOf("stream: Can't allocate memory ERROR").isLeft,
        ClamAvScanner.verdictOf("").isLeft
      )
    }
  )

  /** The real clamd of the pinned image, with its bundled signatures (no freshclam: no network in tests). */
  private val clamd: ZLayer[Any, Throwable, ClamAvScanner] = ZLayer.scoped(
    ZIO
      .acquireRelease(ZIO.attemptBlocking {
        val c = GenericContainer(
          dockerImage = clamavImage,
          exposedPorts = Seq(3310),
          env = Map("CLAMAV_NO_FRESHCLAMD" -> "true"),
          waitStrategy = Wait.forLogMessage(".*clamd started.*", 1).withStartupTimeout(JDuration.ofMinutes(5))
        )
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      .map(c => ClamAvScanner(ClamAvConfig(c.host, c.mappedPort(3310), timeoutSeconds = 60)))
  )

  private val real = suite("against clamd (ClamAV container)")(
    test("a clean file is Clean and the EICAR test file is Infected") {
      for
        scanner  <- ZIO.service[ClamAvScanner]
        _        <- scanner.ping.retry(Schedule.spaced(1.second) && Schedule.recurs(60))
        clean    <- scan(scanner, "Rahmenvertrag über Wartungsleistungen".getBytes(UTF_8))
        infected <- scan(scanner, eicar.getBytes(ISO_8859_1))
        // also inside a larger file
        embedded <- scan(scanner, (eicar + "\r\n").getBytes(ISO_8859_1))
      yield assertTrue(
        clean == Right(ScanVerdict.Clean),
        infected.exists { case ScanVerdict.Infected(s) => s.toLowerCase.contains("eicar"); case _ => false },
        embedded.exists { case ScanVerdict.Infected(_) => true; case _ => false }
      )
    }
  ).provideShared(clamd) @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)

  def spec = suite("ClamAV scanner")(fake, real) @@ TestAspect.withLiveClock @@ TestAspect.sequential
