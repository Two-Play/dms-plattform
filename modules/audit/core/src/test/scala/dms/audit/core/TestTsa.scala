package dms.audit.core

import com.sun.net.httpserver.HttpServer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{AlgorithmIdentifier, ExtendedKeyUsage, Extension, KeyPurposeId}
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** An RFC 3161 timestamp authority on localhost with a self-signed test certificate, so that tests exercise
  * the real HTTP client without calling the internet.
  */
object TestTsa:

  val layer: ZLayer[Any, Throwable, TimestampAuthority] = ZLayer.scoped(
    ZIO
      .acquireRelease(ZIO.attemptBlocking(start()))(server => ZIO.succeed(server.stop(0)))
      .map { server =>
        val url = s"http://127.0.0.1:${server.getAddress.getPort}/tsr"
        HttpTimestampAuthority(TsaConfig(url, 10.seconds), HttpClient.newHttpClient())
      }
  )

  private def start(): HttpServer =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val keys = generator.generateKeyPair()
    val now  = Instant.now()
    val name = X500Name("CN=DMS test TSA")
    val cert = JcaX509CertificateConverter().getCertificate(
      JcaX509v3CertificateBuilder(
        name,
        BigInteger.ONE,
        Date.from(now.minus(1, ChronoUnit.DAYS)),
        Date.from(now.plus(1, ChronoUnit.DAYS)),
        name,
        keys.getPublic
      )
        .addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping))
        .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate))
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
    server
