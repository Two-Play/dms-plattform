package dms.audit.core

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.{TSPAlgorithms, TimeStampRequestGenerator, TimeStampResponse, TimeStampToken}
import zio.*

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.cert.{
  CertPathBuilder,
  CertificateFactory,
  CollectionCertStoreParameters,
  CertStore,
  PKIXBuilderParameters,
  TrustAnchor,
  X509CertSelector,
  X509Certificate
}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Date
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Configuration of the RFC 3161 timestamp authority (TSA).
  *
  * The default URL points to the free service freetsa.org and is meant for development only; production needs
  * a qualified or contractually bound TSA.
  *
  * @param trustedCertificates
  *   PEM files with the CA certificates the TSA's signing certificate must chain to. Tokens are rejected when
  *   this list is empty.
  */
final case class TsaConfig(
    url: String = TsaConfig.DevelopmentUrl,
    timeout: Duration = 30.seconds,
    trustedCertificates: List[String] = Nil
)

object TsaConfig:
  val DevelopmentUrl: String = "https://freetsa.org/tsr"

final case class TimestampError(message: String)

/** An RFC 3161 timestamp authority. */
trait TimestampAuthority:
  /** Identifies the authority in stored anchors. */
  def url: String

  /** Requests a timestamp token over a SHA-256 `digest`; returns the DER-encoded token. */
  def timestamp(digest: Array[Byte]): IO[TimestampError, Array[Byte]]

/** RFC 3161 over HTTP (RFC 3161 section 3.4): POST `application/timestamp-query`. */
final class HttpTimestampAuthority(config: TsaConfig, client: HttpClient) extends TimestampAuthority:
  private val random = SecureRandom()

  def url: String = config.url

  def timestamp(digest: Array[Byte]): IO[TimestampError, Array[Byte]] =
    ZIO
      .attemptBlockingInterrupt {
        val generator = TimeStampRequestGenerator()
        generator.setCertReq(true)
        val request  = generator.generate(TSPAlgorithms.SHA256, digest, BigInteger(64, random))
        val response = client.send(
          HttpRequest
            .newBuilder(URI.create(config.url))
            .timeout(java.time.Duration.ofMillis(config.timeout.toMillis))
            .header("Content-Type", "application/timestamp-query")
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray()
        )
        if response.statusCode() != 200 then Left(s"TSA answered HTTP ${response.statusCode()}")
        else
          val tsr = TimeStampResponse(response.body())
          // Checks status, nonce, message imprint and policy against the request.
          tsr.validate(request)
          Option(tsr.getTimeStampToken).map(_.getEncoded).toRight(s"TSA refused: ${tsr.getStatusString}")
      }
      .mapError(e => TimestampError(s"TSA request to ${config.url} failed: ${e.getMessage}"))
      .flatMap(result => ZIO.fromEither(result).mapError(TimestampError(_)))

object HttpTimestampAuthority:
  val layer: ZLayer[TsaConfig, Nothing, TimestampAuthority] = ZLayer.fromFunction { (config: TsaConfig) =>
    HttpTimestampAuthority(
      config,
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(config.timeout.toMillis)).build()
    )
  }

/** CA certificates a TSA signing certificate must chain to (trust anchors). */
final case class TsaTrust(anchors: List[X509Certificate])

object TsaTrust:
  def fromPem(pem: String): Either[String, TsaTrust] =
    Try(
      CertificateFactory
        .getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        .asScala
        .collect { case c: X509Certificate => c }
        .toList
    ).toEither.left
      .map(e => s"trusted TSA certificates cannot be read: ${e.getMessage}")
      .filterOrElse(_.nonEmpty, "no certificate found in PEM input")
      .map(TsaTrust(_))

  def fromConfig(config: TsaConfig): IO[TimestampError, TsaTrust] =
    ZIO
      .foreach(config.trustedCertificates)(path =>
        ZIO
          .attemptBlocking(Files.readString(Path.of(path)))
          .mapError(e => TimestampError(s"cannot read trusted TSA certificate $path: ${e.getMessage}"))
          .flatMap(pem => ZIO.fromEither(fromPem(pem)).mapError(TimestampError(_)))
      )
      .map(trusts => TsaTrust(trusts.flatMap(_.anchors)))

  val layer: ZLayer[TsaConfig, TimestampError, TsaTrust] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[TsaConfig](fromConfig))

/** Checks of stored RFC 3161 tokens. */
object Rfc3161:

  /** OID of the extended key usage `id-kp-timeStamping` (RFC 3161 section 2.3). */
  private val TimeStampingUsage = "1.3.6.1.5.5.7.3.8"

  /** Verifies that `token` is a well-formed timestamp over `expectedDigest` (SHA-256), that its signature is
    * valid for the signer certificate embedded in the token, and that this certificate is meant for
    * timestamping and chains to one of the trust anchors at the time of the timestamp. Returns the timestamp.
    *
    * Revocation (CRL/OCSP) is not checked.
    */
  def verify(token: Array[Byte], expectedDigest: Array[Byte], trust: TsaTrust): Either[String, Instant] =
    for
      tst <- Try(TimeStampToken(org.bouncycastle.cms.CMSSignedData(token))).toEither.left.map(e =>
        s"token cannot be parsed: ${e.getMessage}"
      )
      info = tst.getTimeStampInfo
      _ <- Either.cond(
        info.getMessageImprintAlgOID == TSPAlgorithms.SHA256,
        (),
        s"unexpected digest algorithm ${info.getMessageImprintAlgOID}"
      )
      _ <- Either.cond(
        MessageDigest.isEqual(info.getMessageImprintDigest, expectedDigest),
        (),
        "token imprint does not match the chain head hash"
      )
      signer <- tst.getCertificates
        .getMatches(null) // all certificates; SignerId is a raw Selector
        .asScala
        .collectFirst { case c: X509CertificateHolder if tst.getSID.`match`(c) => c }
        .toRight("token contains no signer certificate")
      _ <- Try(
        tst.validate(
          JcaSimpleSignerInfoVerifierBuilder().build(JcaX509CertificateConverter().getCertificate(signer))
        )
      ).toEither.left.map(e => s"token signature invalid: ${e.getMessage}")
      converter = JcaX509CertificateConverter()
      signerCert <- Try(converter.getCertificate(signer)).toEither.left.map(e =>
        s"signer certificate: ${e.getMessage}"
      )
      _ <- Either.cond(
        Option(signerCert.getExtendedKeyUsage).exists(_.contains(TimeStampingUsage)),
        (),
        "signer certificate is not meant for timestamping (extended key usage id-kp-timeStamping missing)"
      )
      embedded = tst.getCertificates
        .getMatches(null)
        .asScala
        .collect { case c: X509CertificateHolder =>
          converter.getCertificate(c)
        }
        .toList
      _ <- chainsToTrustAnchor(signerCert, embedded, trust, info.getGenTime)
    yield info.getGenTime.toInstant

  private def chainsToTrustAnchor(
      signer: X509Certificate,
      intermediates: List[X509Certificate],
      trust: TsaTrust,
      at: Date
  ): Either[String, Unit] =
    if trust.anchors.isEmpty then Left("no trusted TSA certificates are configured")
    else
      Try {
        val target = X509CertSelector()
        target.setCertificate(signer)
        val params = PKIXBuilderParameters(trust.anchors.map(TrustAnchor(_, null)).toSet.asJava, target)
        params.setRevocationEnabled(false)
        params.setDate(at)
        params.addCertStore(
          CertStore.getInstance("Collection", CollectionCertStoreParameters((signer :: intermediates).asJava))
        )
        CertPathBuilder.getInstance("PKIX").build(params)
      }.toEither.left.map(e => s"TSA certificate is not trusted: ${e.getMessage}").map(_ => ())
