package dms.integration.webhooks

import zio.*
import zio.test.*

import java.net.InetAddress

object UrlGuardSpec extends ZIOSpecDefault:

  private val strict = WebhookSettings()
  private val dev    = WebhookSettings(allowedHosts = Set("localhost", "127.0.0.1"))

  private def blocked(url: String, settings: WebhookSettings = strict) =
    UrlGuard.validate(url, settings).either.map(_.isLeft)

  def spec = suite("SSRF protection of webhook targets")(
    test("only absolute https URLs without user info; http only for allowed development hosts") {
      assertTrue(
        UrlGuard.parse("https://intranet.example.com/hooks/dms", strict).isRight,
        UrlGuard.parse("http://intranet.example.com/hooks", strict).isLeft,
        UrlGuard.parse("ftp://intranet.example.com/hooks", strict).isLeft,
        UrlGuard.parse("/hooks", strict).isLeft,
        UrlGuard.parse("https://user:pass@intranet.example.com/", strict).isLeft,
        UrlGuard.parse("https://intranet.example.com/#x", strict).isLeft,
        UrlGuard.parse("not a url", strict).isLeft,
        UrlGuard.parse("http://localhost:8099/hook", strict).isLeft,
        UrlGuard.parse("http://localhost:8099/hook", dev).isRight,
        UrlGuard.parse("http://LOCALHOST:8099/hook", dev).isRight
      )
    },
    test("loopback, private, link-local, metadata and reserved addresses are blocked") {
      for
        loopback  <- blocked("https://127.0.0.1/hook")
        localhost <- blocked("https://localhost/hook")
        private10 <- blocked("https://10.1.2.3/hook")
        private19 <- blocked("https://192.168.0.10/hook")
        metadata  <- blocked("https://169.254.169.254/latest/meta-data")
        cgnat     <- blocked("https://100.64.0.1/hook")
        zero      <- blocked("https://0.0.0.0/hook")
        v6Loop    <- blocked("https://[::1]/hook")
        v6Ula     <- blocked("https://[fd00::1]/hook")
        v6Mapped  <- blocked("https://[::ffff:10.0.0.1]/hook")
        public    <- blocked("https://93.184.215.14/hook")
        allowed   <- blocked("http://127.0.0.1:9/hook", dev)
      yield assertTrue(
        loopback,
        localhost,
        private10,
        private19,
        metadata,
        cgnat,
        zero,
        v6Loop,
        v6Ula,
        v6Mapped,
        !public,
        !allowed
      )
    },
    test("private networks can be opened for receivers inside the company network, never loopback") {
      val company = WebhookSettings(allowPrivateNetworks = true)
      for
        privateOk  <- blocked("https://10.1.2.3/hook", company)
        loopback   <- blocked("https://127.0.0.1/hook", company)
        metadata   <- blocked("https://169.254.169.254/", company)
        plainHttp  <- ZIO.succeed(UrlGuard.parse("http://10.1.2.3/hook", company).isLeft)
      yield assertTrue(!privateOk, loopback, metadata, plainHttp)
    },
    test("address classification") {
      def ip(s: String) = InetAddress.getByName(s)
      assertTrue(
        UrlGuard.isPublic(ip("8.8.8.8")),
        UrlGuard.isPublic(ip("2606:4700::1111")),
        !UrlGuard.isPublic(ip("198.18.0.1")),
        !UrlGuard.isPublic(ip("224.0.0.1")),
        !UrlGuard.isPublic(ip("2001:db8::1")),
        UrlGuard.isPrivate(ip("172.16.5.4")),
        !UrlGuard.isPrivate(ip("127.0.0.1"))
      )
    }
  )
