package dms.documents.metadata

import dms.documents.DocumentStatus
import dms.documents.DocumentStatus.*
import zio.test.*

object ProfilesSpec extends ZIOSpecDefault:
  import MetadataFixtures.*

  def spec = suite("Profiles")(
    suite("core profile")(
      test("every class gets the core metadata of business concept 3.2") {
        val profile = resolveOrFail(contract)
        val core    = List(
          "documentId",
          "documentNumber",
          "title",
          "documentClass",
          "version",
          "status",
          "owner",
          "organizationalUnit",
          "scope",
          "validFrom",
          "validUntil",
          "nextReview",
          "confidentiality",
          "allowedGroups",
          "language",
          "retentionRule",
          "containsPersonalData",
          "tags",
          "template",
          "templateVersion",
          "renditionChecksum"
        )
        assertTrue(
          profile.fields.map(_.key.value).take(core.size) == core,
          profile.fields.take(core.size).forall(_.definedBy.isEmpty),
          CoreFields.keys.size == core.size
        )
      },
      test("system fields are not editable, core fields are") {
        assertTrue(
          !CoreFields.definition(key("renditionChecksum")).exists(_.editable),
          !CoreFields.definition(key("documentId")).exists(_.editable),
          CoreFields.definition(key("title")).exists(_.editable)
        )
      },
      test("the rendition checksum is required from approval on, the title always") {
        val profile = resolveOrFail(contract)
        assertTrue(
          profile
            .field("renditionChecksum")
            .exists(f => !f.isRequiredIn(InApproval) && f.isRequiredIn(Approved)),
          profile.field("title").exists(f => f.isRequiredIn(Draft) && f.isRequiredIn(Published)),
          profile.field("tags").exists(_.requiredIn.isEmpty)
        )
      }
    ),
    suite("inheritance")(
      test("a child class gets the parent's fields and rules and adds its own") {
        val profile = resolveOrFail(contract, serviceContract)
        val own     = profile.fields.filter(_.definedBy.isDefined)
        assertTrue(
          own.map(f => (f.key.value, f.definedBy.map(_.value))) == List(
            ("contractPartner", Some("contract")),
            ("contractValue", Some("contract")),
            ("noticePeriod", Some("contract")),
            ("serviceLevel", Some("service-contract"))
          ),
          profile.field("contractPartner").exists(_.isRequiredIn(InReview)),
          profile.classPath.map(_.value) == List("contract", "service-contract"),
          profile.classKey.value == "service-contract"
        )
      },
      test("a child may make an inherited field required in more statuses") {
        val child = serviceContract.copy(fields =
          FieldBinding(contractValue.key, 1, Requiredness.from(InApproval)) :: serviceContract.fields
        )
        val profile = resolveOrFail(contract, child)
        assertTrue(
          profile
            .field("contractValue")
            .exists(f => f.isRequiredIn(InApproval) && f.definedBy.exists(_.value == "contract"))
        )
      },
      test("a child cannot make an inherited required field optional") {
        val child  = serviceContract.copy(fields = List(FieldBinding(partner.key, 1, Set.empty)))
        val result = Profiles.resolve(List(contract, child), lookup)
        assertTrue(
          result.left.exists(
            _ == List(
              ProfileError.RequirementWeakened(child.key, partner.key, Requiredness.from(InReview))
            )
          )
        )
      },
      test("a class cannot make a required core field optional") {
        val cls = contract.copy(fields = List(FieldBinding(key("title"), CoreFields.Version, Set(Published))))
        val result = Profiles.resolve(List(cls), lookup)
        assertTrue(result.left.exists(_.exists {
          case ProfileError.RequirementWeakened(_, field, removed) =>
            field.value == "title" && removed.contains(Draft)
          case _ => false
        }))
      },
      test("a child cannot bind an inherited field to another version") {
        val child  = serviceContract.copy(fields = List(FieldBinding(noticePeriodV2.key, 2)))
        val result = Profiles.resolve(List(contract, child), lookup)
        assertTrue(
          result.left.exists(
            _ == List(ProfileError.InheritedVersionChanged(child.key, noticePeriodV2.key, 1, 2))
          )
        )
      },
      test("unknown definitions and duplicate bindings are rejected") {
        val cls = contract.copy(fields =
          List(FieldBinding(key("missing"), 1), FieldBinding(partner.key, 1), FieldBinding(partner.key, 1))
        )
        val errors = Profiles.resolve(List(cls), lookup).left.getOrElse(Nil)
        assertTrue(
          errors.contains(ProfileError.UnknownFieldDefinition(cls.key, key("missing"), 1)),
          errors.contains(ProfileError.DuplicateBinding(cls.key, partner.key))
        )
      },
      test("a chain whose child does not pin the given parent version is rejected") {
        val child  = serviceContract.copy(parent = Some(ClassRef(contract.classId, 7)))
        val result = Profiles.resolve(List(contract, child), lookup)
        assertTrue(result.left.exists(_.exists(_.isInstanceOf[ProfileError.BrokenChain])))
      }
    ),
    suite("field definitions")(
      test("tenants cannot define core field keys") {
        val d = partner.copy(key = key("title"))
        assertTrue(Profiles.checkDefinition(d).exists(_.contains("core field")))
      },
      test("rules are checked: label, pattern, ranges, options, currencies") {
        val problems = List(
          partner.copy(label = " "),
          partner.copy(fieldType = FieldType.Text(pattern = Some("[unclosed"))),
          partner.copy(fieldType = FieldType.Number(Some(5), Some(1))),
          partner.copy(fieldType = FieldType.Amount(currencies = List("euro"))),
          partner.copy(fieldType = FieldType.Selection(List(SelectOption("a", "A"), SelectOption("a", "B")))),
          partner.copy(fieldType = FieldType.Selection(Nil)),
          partner.copy(fieldType =
            FieldType.Date(Some(java.time.LocalDate.of(2026, 2, 1)), Some(java.time.LocalDate.of(2026, 1, 1)))
          )
        ).map(Profiles.checkDefinition)
        assertTrue(problems.forall(_.nonEmpty), Profiles.checkDefinition(partner).isEmpty)
      },
      test("field keys and class keys have a stable format") {
        assertTrue(
          FieldKey.parse("contractPartner").isRight,
          FieldKey.parse("Contract partner").isLeft,
          ClassKey.parse("service-contract").isRight,
          ClassKey.parse("Service_Contract").isLeft
        )
      }
    ),
    suite("versioning")(
      test("a change creates the next version and keeps the previous one unchanged") {
        val next = Profiles.nextVersion(partner, partner.copy(label = "Partner", version = 99))
        assertTrue(
          next.exists(d => d.version == 2 && d.label == "Partner" && d.key == partner.key),
          partner.version == 1 && partner.label == "Contract partner"
        )
      },
      test("removed selection options stay readable as inactive options") {
        assertTrue(
          noticePeriodV2.version == 2,
          noticePeriodV2.fieldType == FieldType.Selection(
            List(
              SelectOption("1m", "One month"),
              SelectOption("3m", "Three months"),
              SelectOption("6m", "Six months", active = false)
            )
          )
        )
      },
      test("the type kind cannot change") {
        val result = Profiles.nextVersion(partner, partner.copy(fieldType = FieldType.Date()))
        assertTrue(result.left.exists(_.exists(_.contains("cannot change"))))
      },
      test("documents of an older profile version keep the field version they used") {
        val v2 = contract.copy(
          version = 2,
          fields = contract.fields.map { b =>
            if b.key == noticePeriodV2.key then b.copy(version = 2) else b
          }
        )
        val old     = resolveOrFail(contract)
        val current = resolveOrFail(v2)
        assertTrue(
          old.field("noticePeriod").map(_.definition.version).contains(1),
          current.field("noticePeriod").map(_.definition.version).contains(2),
          old.version == 1,
          current.version == 2
        )
      },
      test("Requiredness.from covers the rest of the regular path but not withdrawal") {
        assertTrue(
          Requiredness.from(InReview) == Set(
            InReview,
            InApproval,
            Approved,
            Published,
            ReviewDue,
            Superseded,
            Archived
          ),
          !Requiredness.always.contains(DocumentStatus.Withdrawn),
          Requiredness.from(Withdrawn) == Set(Withdrawn)
        )
      }
    )
  )
