package dms.integration.partners

import dms.kernel.TenantId
import zio.*

import java.util.Locale

/** A partner master held in memory: for development without a business system (`dms.partners.directory =
  * demo`) and for tests. The same partners for every tenant.
  */
final class InMemoryPartnerDirectory(partners: List[Partner]) extends PartnerDirectory:

  def search(tenant: TenantId, query: String, limit: Int): IO[PartnerDirectoryError, List[Partner]] =
    val q = query.trim.toLowerCase(Locale.ROOT)
    ZIO.succeed(
      partners
        .filter(p => p.name.toLowerCase(Locale.ROOT).contains(q) || p.id.toLowerCase(Locale.ROOT).contains(q))
        .sortBy(p => (p.name.toLowerCase(Locale.ROOT), p.id))
        .take(limit.max(0))
    )

  def get(tenant: TenantId, id: String): IO[PartnerDirectoryError, Option[Partner]] =
    ZIO.succeed(partners.find(_.id == id))

object InMemoryPartnerDirectory:
  /** Fictitious partners for local development and demos. */
  val demoPartners: List[Partner] = List(
    Partner(
      "P-10001",
      "Nordlicht Logistik GmbH",
      PostalAddress(Some("Hafenstraße 12"), Some("20457"), Some("Hamburg"), Some("DE")),
      Some("DE123456789")
    ),
    Partner(
      "P-10002",
      "Beispiel Software AG",
      PostalAddress(Some("Musterweg 5"), Some("80331"), Some("München"), Some("DE")),
      Some("DE987654321")
    ),
    Partner(
      "P-10003",
      "Muster & Partner KG",
      PostalAddress(Some("Am Markt 3"), Some("04109"), Some("Leipzig"), Some("DE")),
      None
    ),
    Partner(
      "P-20001",
      "Example Facility Services Ltd",
      PostalAddress(Some("1 Sample Road"), Some("EC1A 1AA"), Some("London"), Some("GB")),
      None
    ),
    Partner(
      "P-20002",
      "Alpenblick Bau AG",
      PostalAddress(Some("Seestrasse 40"), Some("8002"), Some("Zürich"), Some("CH")),
      Some("CHE-123.456.789")
    )
  )

  val demo: InMemoryPartnerDirectory = InMemoryPartnerDirectory(demoPartners)
