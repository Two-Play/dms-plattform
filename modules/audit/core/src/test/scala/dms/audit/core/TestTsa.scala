package dms.audit.core

import com.sun.net.httpserver.HttpServer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{
  AlgorithmIdentifier,
  BasicConstraints,
  ExtendedKeyUsage,
  Extension,
  KeyPurposeId,
  KeyUsage
}
import org.bouncycastle.cert.jcajce.{JcaCertStore, JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.{JcaContentSignerBuilder, JcaDigestCalculatorProviderBuilder}
import org.bouncycastle.tsp.{
  TSPAlgorithms,
  TimeStampRequest,
  TimeStampResponseGenerator,
  TimeStampTokenGenerator
}
import zio.*

import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** An RFC 3161 timestamp authority on localhost whose signing certificate is issued by a test CA, so that
  * tests exercise the real HTTP client and certificate path validation without calling the internet.
  */
object TestTsa:

  /** A running test TSA and the CA certificate that issued its signing certificate. */
  final case class Instance(authority: TimestampAuthority, ca: X509Certificate):
    def trust: TsaTrust = TsaTrust(List(ca))

  /** A test TSA whose signing certificate is issued by a CA named `caName`. */
  def make(caName: String): ZIO[Scope, Throwable, Instance] =
    ZIO
      .acquireRelease(ZIO.attemptBlocking(start(caName)))((server, _) => ZIO.succeed(server.stop(0)))
      .map { (server, ca) =>
        val url = s"http://127.0.0.1:${server.getAddress.getPort}/tsr"
        Instance(HttpTimestampAuthority(TsaConfig(url, 10.seconds), HttpClient.newHttpClient()), ca)
      }

  val layer: ZLayer[Any, Throwable, TimestampAuthority & TsaTrust] =
    ZLayer
      .scoped(make("DMS test CA"))
      .flatMap(env => ZLayer.succeed(env.get.authority) ++ ZLayer.succeed(env.get.trust))

  private def rsaKeys() =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private def start(caName: String): (HttpServer, X509Certificate) =
    val now       = Instant.now()
    val notBefore = Date.from(now.minus(1, ChronoUnit.DAYS))
    val notAfter  = Date.from(now.plus(1, ChronoUnit.DAYS))
    val caKeys    = rsaKeys()
    val caSubject = X500Name(s"CN=$caName")
    val ca        = JcaX509CertificateConverter().getCertificate(
      JcaX509v3CertificateBuilder(caSubject, BigInteger.ONE, notBefore, notAfter, caSubject, caKeys.getPublic)
        .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
        .build(JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate))
    )
    val keys    = rsaKeys()
    val builder = JcaX509v3CertificateBuilder(
      caSubject,
      BigInteger.TWO,
      notBefore,
      notAfter,
      X500Name("CN=DMS test TSA"),
      keys.getPublic
    ).addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
      .addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
    val cert = JcaX509CertificateConverter().getCertificate(
      builder.build(JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate))
    )
    val digests    = JcaDigestCalculatorProviderBuilder().build()
    val signerInfo =
      JcaSignerInfoGeneratorBuilder(digests).build(
        JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate),
        cert
      )
    val tokens = TimeStampTokenGenerator(
      signerInfo,
      digests.get(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
      ASN1ObjectIdentifier("1.2.3.4.1")
    )
    tokens.addCertificates(JcaCertStore(java.util.List.of(cert)))
    val responses = TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED)
    val serial    = AtomicLong()

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/tsr",
      exchange =>
        try
          val request  = TimeStampRequest(exchange.getRequestBody.readAllBytes())
          val response = responses.synchronized(
            responses.generate(request, BigInteger.valueOf(serial.incrementAndGet()), Date())
          )
          val body = response.getEncoded
          exchange.getResponseHeaders.add("Content-Type", "application/timestamp-reply")
          exchange.sendResponseHeaders(200, body.length.toLong)
          exchange.getResponseBody.write(body)
        finally exchange.close()
    ): Unit
    server.start()
    (server, ca)
