package dms.documents.metadata

import dms.documents.DocumentStatus

/** Field definitions and classes shared by the metadata tests: contract → service contract. */
object MetadataFixtures:
  def key(k: String): FieldKey = FieldKey.unsafe(k)

  val partner: FieldDefinition =
    FieldDefinition(
      key("contractPartner"),
      1,
      "Contract partner",
      None,
      FieldType.Text(maxLength = Some(100))
    )

  val contractValue: FieldDefinition = FieldDefinition(
    key("contractValue"),
    1,
    "Contract value",
    None,
    FieldType.Amount(min = Some(BigDecimal(0)), currencies = List("EUR", "USD"))
  )

  val noticePeriodV1: FieldDefinition = FieldDefinition(
    key("noticePeriod"),
    1,
    "Notice period",
    None,
    FieldType.Selection(
      List(
        SelectOption("1m", "One month"),
        SelectOption("3m", "Three months"),
        SelectOption("6m", "Six months")
      )
    )
  )

  /** Version 2 dropped option `6m`; it stays as an inactive option. */
  val noticePeriodV2: FieldDefinition = Profiles
    .nextVersion(
      noticePeriodV1,
      noticePeriodV1.copy(fieldType =
        FieldType.Selection(List(SelectOption("1m", "One month"), SelectOption("3m", "Three months")))
      )
    )
    .fold(e => throw IllegalStateException(e.mkString), identity)

  val serviceLevel: FieldDefinition =
    FieldDefinition(
      key("serviceLevel"),
      1,
      "Service level",
      None,
      FieldType.Number(Some(0), Some(100), Some(1))
    )

  val definitions: List[FieldDefinition] =
    List(partner, contractValue, noticePeriodV1, noticePeriodV2, serviceLevel)

  def lookup(k: FieldKey, v: Int): Option[FieldDefinition] =
    definitions.find(d => d.key == k && d.version == v)

  val contract: DocumentClassVersion = DocumentClassVersion(
    DocumentClassId.random(),
    ClassKey.unsafe("contract"),
    1,
    None,
    "Contract",
    None,
    List(
      FieldBinding(partner.key, 1, Requiredness.from(DocumentStatus.InReview)),
      FieldBinding(contractValue.key, 1),
      FieldBinding(noticePeriodV1.key, 1)
    )
  )

  val serviceContract: DocumentClassVersion = DocumentClassVersion(
    DocumentClassId.random(),
    ClassKey.unsafe("service-contract"),
    1,
    Some(contract.ref),
    "Service contract",
    None,
    List(FieldBinding(serviceLevel.key, 1, Requiredness.from(DocumentStatus.InApproval)))
  )

  def resolveOrFail(chain: DocumentClassVersion*): MetadataProfile =
    Profiles
      .resolve(chain.toList, lookup)
      .fold(e => throw IllegalStateException(e.map(_.message).mkString), identity)
