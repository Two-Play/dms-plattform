package dms.documents

/** Which version a stable address of a document stands for (US-14.1, DMS-58; ADR 0035): the version that is
  * valid now in the requested validity scope, resolved at request time, so that a link never shows an
  * outdated version.
  */
object CurrentVersion:

  enum Resolution:
    /** The valid version (Published or ReviewDue). */
    case Found(version: DocumentVersion)

    /** No scope was asked for, or the scope matches several valid versions: the caller has to pick one. */
    case Ambiguous(scopes: List[String])

    /** Nothing is valid in the scope any more: the last valid version was withdrawn without a successor. */
    case Withdrawn(version: DocumentVersion)

    /** No version was ever valid in the scope. */
    case NotFound

  /** Statuses of versions that ended without a successor: withdrawn, and archived after the withdrawal. */
  private val Ended: Set[DocumentStatus] = Set(DocumentStatus.Withdrawn, DocumentStatus.Archived)

  /** The parts of a scope key (see [[Versioning.scopeKey]]), e.g. `AT|DE` → `AT`, `DE`. */
  def scopeParts(scope: String): Set[String] = scope.split('|').map(_.trim).filter(_.nonEmpty).toSet

  /** Whether a version of scope key `scope` applies to the requested scope: the same key, or one of its parts
    * (a version for `AT|DE` applies to `DE`).
    */
  def appliesTo(scope: String, requested: String): Boolean =
    scope == requested || scopeParts(scope).contains(requested)

  /** Resolves the current version among `versions` (the caller leaves out what it may not read).
    *
    * With a scope: the valid version whose scope key is the scope, else the only valid version whose scope
    * key contains it. Without a scope: the valid version without restriction (empty scope), else the only
    * valid version. If nothing is valid, a version that was valid and is now withdrawn (or archived after the
    * withdrawal) makes the resolution [[Resolution.Withdrawn]].
    */
  def resolve(versions: List[DocumentVersion], scope: Option[String]): Resolution =
    val requested = scope.map(_.trim).filter(_.nonEmpty)
    val matching  = requested.fold(versions)(s => versions.filter(v => appliesTo(v.scope, s)))
    val valid     = matching.filter(v => Versioning.isValid(v.status))
    val preferred = requested.fold(valid.filter(_.scope.isEmpty))(s => valid.filter(_.scope == s))
    (preferred, valid) match
      case (List(v), _)   => Resolution.Found(v)
      case (_, List(v))   => Resolution.Found(v)
      case (_, v :: more) => Resolution.Ambiguous((v :: more).map(_.scope).distinct.sorted)
      case (_, Nil)       =>
        matching
          .filter(v => Ended.contains(v.status) && v.publishedAt.isDefined)
          .maxByOption(_.number)
          .fold(Resolution.NotFound)(Resolution.Withdrawn(_))
