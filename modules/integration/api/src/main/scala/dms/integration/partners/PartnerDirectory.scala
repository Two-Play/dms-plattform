package dms.integration.partners

import dms.kernel.TenantId
import zio.*

/** Postal address of a partner as the partner master holds it; parts may be missing. */
final case class PostalAddress(
    street: Option[String],
    postalCode: Option[String],
    city: Option[String],
    /** ISO 3166-1 alpha-2 code, e.g. `DE`. */
    country: Option[String]
):
  /** One line, e.g. `Hafenstraße 12, 20457 Hamburg, DE`; empty parts are left out. */
  def singleLine: String =
    val place = List(postalCode, city).flatten.map(_.trim).filter(_.nonEmpty).mkString(" ")
    List(street.map(_.trim).getOrElse(""), place, country.map(_.trim).getOrElse(""))
      .filter(_.nonEmpty)
      .mkString(", ")

/** An entry of the partner master (customer, supplier, contract partner).
  *
  * @param id
  *   the identifier in the partner master (partner number), stable and unique per tenant
  */
final case class Partner(id: String, name: String, address: PostalAddress, vatId: Option[String]):

  /** The value of an attribute that a reference field can take over (`ReferenceSource.PartnerMaster` in
    * templates-api lists the same names); `None` if the partner has no value for it or the name is unknown.
    */
  def attribute(key: String): Option[String] =
    (key match
      case "id"         => Some(id)
      case "name"       => Some(name)
      case "street"     => address.street
      case "postalCode" => address.postalCode
      case "city"       => address.city
      case "country"    => address.country
      case "address"    => Some(address.singleLine)
      case "vatId"      => vatId
      case _            => None
    ).map(_.trim).filter(_.nonEmpty)

object Partner:
  /** The attributes [[Partner.attribute]] knows. */
  val Attributes: List[String] =
    List("id", "name", "street", "postalCode", "city", "country", "address", "vatId")

/** Why the partner master could not answer. Both are temporary from the author's point of view: the draft can
  * be saved without the partner and the partner picked later.
  */
enum PartnerDirectoryError:
  /** Not reachable, timed out, answered with an error status, or the circuit breaker is open. */
  case Unavailable(reason: String)

  /** Answered with a payload that does not fit the agreed format. */
  case InvalidResponse(reason: String)

/** Port to the partner master of a connected business system (business concept ch. 10, US-03.1, ADR 0023).
  *
  * Lookups are live: nothing is cached. What a document keeps is the snapshot taken over into its field
  * values when the author picks a partner. Implementations must fail fast with [[PartnerDirectoryError]]
  * instead of hanging when the partner master does not answer.
  */
trait PartnerDirectory:
  /** Partners whose name or identifier contains `query` (case-insensitive), at most `limit`, by name. */
  def search(tenant: TenantId, query: String, limit: Int): IO[PartnerDirectoryError, List[Partner]]

  /** The partner with identifier `id`, or `None` if the partner master does not know it. */
  def get(tenant: TenantId, id: String): IO[PartnerDirectoryError, Option[Partner]]

object PartnerDirectory:
  /** Source name of taken-over values (`ValueOrigin.System("partner-master")`). */
  val Source = "partner-master"

  /** Shortest search text; shorter queries would return most of the partner master. */
  val MinQueryLength = 2

  val MaxResults = 50
