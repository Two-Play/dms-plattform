package dms.documents.metadata

import dms.documents.DocumentStatus

import java.util.regex.PatternSyntaxException

/** Why a class version cannot be resolved into a profile. */
enum ProfileError:
  /** A class version does not pin the version given as its parent in the chain. */
  case BrokenChain(classKey: ClassKey, detail: String)
  case UnknownFieldDefinition(classKey: ClassKey, field: FieldKey, version: Int)
  case DuplicateBinding(classKey: ClassKey, field: FieldKey)

  /** A child binds an inherited field to another definition version than its ancestor. */
  case InheritedVersionChanged(classKey: ClassKey, field: FieldKey, inherited: Int, requested: Int)

  /** A child drops statuses in which an inherited field is required. */
  case RequirementWeakened(classKey: ClassKey, field: FieldKey, removed: Set[DocumentStatus])

  def message: String = this match
    case BrokenChain(c, detail)          => s"class $c: $detail"
    case UnknownFieldDefinition(c, f, v) => s"class $c: field definition $f version $v does not exist"
    case DuplicateBinding(c, f)          => s"class $c: field $f is bound more than once"
    case InheritedVersionChanged(c, f, inherited, v) =>
      s"class $c: field $f is inherited in version $inherited and cannot be bound to version $v"
    case RequirementWeakened(c, f, removed) =>
      s"class $c: field $f is required in ${removed.toList.map(_.toString).sorted.mkString(", ")} " +
        "by a parent class or the core profile and cannot be made optional"

/** Pure rules for field definitions and the resolution of effective profiles. */
object Profiles:

  /** Resolves the effective profile of the last class version in `chain`.
    *
    * @param chain
    *   class versions from the root class to the class itself; each element pins the previous one as parent
    * @param definitions
    *   lookup of tenant field definitions by key and version (core fields are built in)
    */
  def resolve(
      chain: List[DocumentClassVersion],
      definitions: (FieldKey, Int) => Option[FieldDefinition]
  ): Either[List[ProfileError], MetadataProfile] =
    chain.lastOption match
      case None       => Left(Nil)
      case Some(leaf) =>
        val chainErrors = chainProblems(chain)
        if chainErrors.nonEmpty then Left(chainErrors)
        else
          val (fields, errors) = chain.foldLeft((CoreFields.fields, List.empty[ProfileError])) {
            case ((inherited, errors), cls) =>
              val (next, own) = applyBindings(cls, inherited, definitions)
              (next, errors ++ own)
          }
          if errors.nonEmpty then Left(errors)
          else Right(MetadataProfile(leaf.classId, leaf.key, leaf.version, chain.map(_.key), fields))

  private def chainProblems(chain: List[DocumentClassVersion]): List[ProfileError] =
    val rootProblem = chain.headOption.collect {
      case root if root.parent.isDefined => ProfileError.BrokenChain(root.key, "the root class has a parent")
    }
    val linkProblems = chain.zip(chain.drop(1)).collect {
      case (parent, child) if !child.parent.contains(parent.ref) =>
        ProfileError.BrokenChain(child.key, s"parent ${parent.key} version ${parent.version} is not pinned")
    }
    rootProblem.toList ++ linkProblems

  private def applyBindings(
      cls: DocumentClassVersion,
      inherited: List[ProfileField],
      definitions: (FieldKey, Int) => Option[FieldDefinition]
  ): (List[ProfileField], List[ProfileError]) =
    val duplicates = cls.fields.groupBy(_.key).collect {
      case (key, bindings) if bindings.size > 1 => ProfileError.DuplicateBinding(cls.key, key)
    }
    val results = cls.fields.distinctBy(_.key).map { binding =>
      inherited.find(_.key == binding.key) match
        case Some(parentField) => tighten(cls, parentField, binding)
        case None              =>
          definitions(binding.key, binding.version)
            .toRight(ProfileError.UnknownFieldDefinition(cls.key, binding.key, binding.version))
            .map(d => ProfileField(d, binding.requiredIn, Some(cls.key)))
    }
    val updates = results.collect { case Right(f) => f }
    val merged  =
      inherited.map(f => updates.find(_.key == f.key).getOrElse(f)) ++
        updates.filterNot(u => inherited.exists(_.key == u.key))
    (merged, duplicates.toList.sortBy(_.message) ++ results.collect { case Left(e) => e })

  /** A child may re-bind an inherited field only to add statuses in which it is required. */
  private def tighten(
      cls: DocumentClassVersion,
      parentField: ProfileField,
      binding: FieldBinding
  ): Either[ProfileError, ProfileField] =
    val removed = parentField.requiredIn -- binding.requiredIn
    if binding.version != parentField.definition.version then
      Left(
        ProfileError.InheritedVersionChanged(
          cls.key,
          binding.key,
          parentField.definition.version,
          binding.version
        )
      )
    else if removed.nonEmpty then Left(ProfileError.RequirementWeakened(cls.key, binding.key, removed))
    else Right(parentField.copy(requiredIn = binding.requiredIn))

  /** Problems of a field definition on its own (label, rules, options). */
  def checkDefinition(definition: FieldDefinition): List[String] =
    val label =
      Option.when(definition.label.trim.isEmpty)("the label must not be empty").toList
    val reserved =
      Option
        .when(definition.origin == FieldOrigin.Tenant && CoreFields.isCore(definition.key))(
          s"${definition.key} is a core field and cannot be defined by a tenant"
        )
        .toList
    label ++ reserved ++ checkType(definition.fieldType)

  private def checkType(fieldType: FieldType): List[String] = fieldType match
    case FieldType.Text(maxLength, pattern) =>
      maxLength.filter(_ < 1).map(_ => "maxLength must be at least 1").toList ++
        pattern.flatMap(p => compileError(p)).toList
    case FieldType.Number(min, max, scale) =>
      range(min, max) ++ scale.filter(_ < 0).map(_ => "scale must not be negative").toList
    case FieldType.Amount(min, max, currencies) =>
      range(min, max) ++
        currencies.filterNot(Validation.isCurrencyCode).map(c => s"'$c' is not an ISO 4217 currency code")
    case FieldType.Date(min, max) =>
      (min, max) match
        case (Some(a), Some(b)) if a.isAfter(b) => List("min must not be after max")
        case _                                  => Nil
    case FieldType.Selection(options) =>
      val codes = options.map(_.code)
      Option.when(!options.exists(_.active))("a selection needs at least one active option").toList ++
        codes.diff(codes.distinct).distinct.map(c => s"option code '$c' is used more than once") ++
        options
          .filter(o => o.code.trim.isEmpty || o.label.trim.isEmpty)
          .map(_ => "options need a code and a label")
    case FieldType.YesNo | FieldType.PersonRef | FieldType.DocumentRef => Nil

  private def range(min: Option[BigDecimal], max: Option[BigDecimal]): List[String] = (min, max) match
    case (Some(a), Some(b)) if a > b => List("min must not be greater than max")
    case _                           => Nil

  private def compileError(pattern: String): Option[String] =
    try
      java.util.regex.Pattern.compile(pattern)
      None
    catch case e: PatternSyntaxException => Some(s"invalid pattern: ${e.getDescription}")

  /** Builds the next version of a field definition from the requested changes.
    *
    * The type kind stays the same (a different kind is a different field). Selection options missing from the
    * request are kept as inactive, so existing values stay readable.
    */
  def nextVersion(
      previous: FieldDefinition,
      requested: FieldDefinition
  ): Either[List[String], FieldDefinition] =
    if requested.fieldType.kind != previous.fieldType.kind then
      Left(
        List(
          s"the type of ${previous.key} cannot change from ${previous.fieldType.kind} to ${requested.fieldType.kind}; " +
            "define a new field instead"
        )
      )
    else
      val fieldType = (previous.fieldType, requested.fieldType) match
        case (FieldType.Selection(old), FieldType.Selection(current)) =>
          val retired = old.filterNot(o => current.exists(_.code == o.code)).map(_.copy(active = false))
          FieldType.Selection(current ++ retired)
        case (_, t) => t
      val next = requested.copy(
        key = previous.key,
        version = previous.version + 1,
        origin = previous.origin,
        fieldType = fieldType
      )
      val problems = checkDefinition(next)
      Either.cond(problems.isEmpty, next, problems)
