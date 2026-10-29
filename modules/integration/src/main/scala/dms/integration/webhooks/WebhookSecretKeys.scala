package dms.integration.webhooks

import dms.kernel.TenantId

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, UUID}
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Try

/** A signing secret as stored in `dms.webhook_subscription` (ADR 0035, DMS-107). */
enum StoredSecret:
  /** Encrypted with AES-256-GCM under the platform key `keyId`: base64 of nonce (12 bytes), ciphertext and
    * tag.
    */
  case Sealed(keyId: String, ciphertext: String)

  /** Plain text from before migration V29; re-encrypted at startup ([[WebhookSecretMigration]]). */
  case Plain(secret: String)

  def encryptedWith: Option[String] = this match
    case Sealed(id, _) => Some(id)
    case Plain(_)      => None

  /** Never shows the secret or the ciphertext, e.g. if the value ends up in a log line. */
  override def toString: String = this match
    case Sealed(id, _) => s"StoredSecret.Sealed($id)"
    case Plain(_)      => "StoredSecret.Plain(***)"

/** The platform keys that encrypt webhook signing secrets at rest (DMS-107, ADR 0035).
  *
  * Secrets are encrypted with AES-256-GCM under the active key. The additional authenticated data binds a
  * ciphertext to its tenant and subscription, so a ciphertext copied to another row does not decrypt. Older
  * keys stay in the ring for decryption only; [[WebhookSecretMigration]] re-encrypts secrets of older keys
  * (and plain-text secrets from before V29) with the active key at startup, so a key can be retired once no
  * row uses it any more.
  *
  * Without configured keys the well-known development key [[WebhookSecretKeys.DevelopmentKeyId]] is active,
  * which is no protection; production must configure keys (see `modules/integration/README.md`). The
  * development key stays readable when real keys are configured, so switching to them re-encrypts the rows.
  */
final class WebhookSecretKeys private (val activeKeyId: String, keys: Map[String, SecretKeySpec]):
  import WebhookSecretKeys.*

  /** Whether the active key is the development key, i.e. secrets are not protected. */
  def isDevelopment: Boolean = activeKeyId == DevelopmentKeyId

  def keyIds: Set[String] = keys.keySet

  /** Encrypts `secret` for the subscription `subscription` of `tenant` with the active key. */
  def seal(secret: String, tenant: TenantId, subscription: UUID): StoredSecret.Sealed =
    val nonce = new Array[Byte](NonceBytes)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance(Transformation)
    cipher.init(Cipher.ENCRYPT_MODE, keys(activeKeyId), GCMParameterSpec(TagBits, nonce))
    cipher.updateAAD(context(tenant, subscription))
    val encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8))
    StoredSecret.Sealed(activeKeyId, Base64.getEncoder.encodeToString(nonce ++ encrypted))

  /** The secret in clear text, or why it cannot be decrypted (unknown key, wrong row, tampered). */
  def open(stored: StoredSecret, tenant: TenantId, subscription: UUID): Either[String, String] =
    stored match
      case StoredSecret.Plain(secret)          => Right(secret)
      case StoredSecret.Sealed(id, ciphertext) =>
        for
          key <- keys
            .get(id)
            .toRight(s"the signing secret is encrypted with the unknown key '$id'; configure that key")
          bytes <- Try(Base64.getDecoder.decode(ciphertext)).toOption
            .filter(_.length > NonceBytes)
            .toRight("the stored signing secret is malformed")
          plain <- Try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TagBits, bytes.take(NonceBytes)))
            cipher.updateAAD(context(tenant, subscription))
            cipher.doFinal(bytes.drop(NonceBytes))
          }.toEither.left.map(_ =>
            s"the signing secret cannot be decrypted with key '$id' (wrong key or altered data)"
          )
        yield String(plain, StandardCharsets.UTF_8)

  /** Whether a stored secret should be re-encrypted: plain text, or encrypted with another than the active
    * key.
    */
  def needsResealing(stored: StoredSecret): Boolean = !stored.encryptedWith.contains(activeKeyId)

  /** Names the keys, never their material. */
  override def toString: String =
    s"WebhookSecretKeys(active=$activeKeyId, keys=${keys.keySet.toList.sorted.mkString(",")})"

object WebhookSecretKeys:
  /** Id of the built-in development key. */
  val DevelopmentKeyId = "dev"

  private val Transformation = "AES/GCM/NoPadding"
  private val NonceBytes     = 12
  private val TagBits        = 128
  private val KeyBytes       = 32
  private val KeyIdPattern   = "[A-Za-z0-9._-]{1,32}".r
  private val random         = SecureRandom()

  /** A fixed, publicly known key: only for development and tests. */
  private val developmentKey: SecretKeySpec =
    SecretKeySpec(
      MessageDigest
        .getInstance("SHA-256")
        .digest(
          "dms-platform webhook secrets: development key, not for production".getBytes(StandardCharsets.UTF_8)
        ),
      "AES"
    )

  /** Only the development key. */
  val development: WebhookSecretKeys =
    WebhookSecretKeys(DevelopmentKeyId, Map(DevelopmentKeyId -> developmentKey))

  /** Parses the configured key ring `id:base64key,id:base64key,…`: the first entry is the active key, the
    * others only decrypt. Every key is 32 random bytes (AES-256), base64 encoded; ids consist of letters,
    * digits, `.`, `_` and `-`. Empty: the development key.
    */
  def parse(spec: String): Either[String, WebhookSecretKeys] =
    val entries = spec.split("[,\\s]+").map(_.trim).filter(_.nonEmpty).toList
    if entries.isEmpty then Right(development)
    else
      entries
        .foldLeft[Either[String, List[(String, SecretKeySpec)]]](Right(Nil)) { (acc, entry) =>
          acc.flatMap(keys => key(entry).map(keys :+ _))
        }
        .flatMap { keys =>
          val ids = keys.map(_._1)
          if ids.distinct.size != ids.size then Left("webhook secret key ids must be unique")
          else if ids.contains(DevelopmentKeyId) then
            Left(s"the webhook secret key id '$DevelopmentKeyId' is reserved for the development key")
          else Right(WebhookSecretKeys(ids.head, ((DevelopmentKeyId -> developmentKey) :: keys).toMap))
        }

  /** A new random key in the configuration format, for operators (`id:base64`). */
  def generate(id: String): String =
    val bytes = new Array[Byte](KeyBytes)
    random.nextBytes(bytes)
    s"$id:${Base64.getEncoder.encodeToString(bytes)}"

  private def key(entry: String): Either[String, (String, SecretKeySpec)] =
    entry.split(":", 2) match
      case Array(id, material) if KeyIdPattern.matches(id) =>
        Try(Base64.getDecoder.decode(material.trim)).toOption
          .filter(_.length == KeyBytes)
          .map(bytes => id -> SecretKeySpec(bytes, "AES"))
          .toRight(s"the webhook secret key '$id' must be $KeyBytes bytes, base64 encoded")
      case _ =>
        Left(
          "webhook secret keys must be given as <id>:<base64 key>, ids of letters, digits, '.', '_' and '-'"
        )

  private def context(tenant: TenantId, subscription: UUID): Array[Byte] =
    s"dms.webhook-secret.v1:${tenant.value}:$subscription".getBytes(StandardCharsets.UTF_8)
