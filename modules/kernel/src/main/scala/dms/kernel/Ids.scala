package dms.kernel

import java.util.UUID

/** Domain identifiers as opaque types: UUIDs at runtime, but distinct types for the compiler. */
opaque type TenantId = UUID
object TenantId:
  def apply(value: UUID): TenantId         = value
  def random(): TenantId                   = UUID.randomUUID()
  extension (id: TenantId) def value: UUID = id

opaque type DocumentId = UUID
object DocumentId:
  def apply(value: UUID): DocumentId         = value
  def random(): DocumentId                   = UUID.randomUUID()
  extension (id: DocumentId) def value: UUID = id

opaque type TemplateId = UUID
object TemplateId:
  def apply(value: UUID): TemplateId         = value
  def random(): TemplateId                   = UUID.randomUUID()
  extension (id: TemplateId) def value: UUID = id

/** User identifier from ZITADEL (`sub` claim), hence String instead of UUID. */
opaque type UserId = String
object UserId:
  def apply(value: String): UserId         = value
  extension (id: UserId) def value: String = id
