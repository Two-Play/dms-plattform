package dms.search.core

import dms.authz.{Confidentiality, Resource, Role, Subject}
import dms.search.AccessAttributes
import dms.kernel.UserId
import zio.json.ast.Json

import java.time.Instant

/** A boolean filter over keyword fields of the index: the subset of the OpenSearch query DSL the permission
  * filter uses. It can be rendered as a query ([[toJson]]) and evaluated in memory ([[matches]]), which the
  * tests use to compare it with [[dms.authz.Policy]] and with OpenSearch.
  */
enum Filter:
  case All
  case Nothing

  /** The field has at least one of `values` (OpenSearch `terms`). */
  case AnyOf(field: String, values: Set[String])
  case And(filters: List[Filter])
  case Or(filters: List[Filter])

  /** Evaluates the filter against the keyword values of an index entry, by field. */
  def matches(fields: Map[String, Set[String]]): Boolean = this match
    case All                  => true
    case Nothing              => false
    case AnyOf(field, values) => fields.getOrElse(field, Set.empty).exists(values.contains)
    case And(filters)         => filters.forall(_.matches(fields))
    case Or(filters)          => filters.exists(_.matches(fields))

  def toJson: Json = this match
    case All                                => Json.Obj("match_all" -> Json.Obj())
    case Nothing                            => Json.Obj("match_none" -> Json.Obj())
    case AnyOf(_, values) if values.isEmpty => Filter.Nothing.toJson
    case AnyOf(field, values)               =>
      Json.Obj("terms" -> Json.Obj(field -> Json.Arr(values.toList.sorted.map(Json.Str(_))*)))
    case And(filters) =>
      Json.Obj("bool" -> Json.Obj("filter" -> Json.Arr(filters.map(_.toJson)*)))
    case Or(filters) =>
      Json.Obj(
        "bool" -> Json.Obj(
          "should"               -> Json.Arr(filters.map(_.toJson)*),
          "minimum_should_match" -> Json.Num(1)
        )
      )

object Filter:
  def anyOf(field: String, values: Iterable[String]): Filter = AnyOf(field, values.toSet)
  def is(field: String, value: String): Filter               = AnyOf(field, Set(value))

  /** `And` without the trivially true parts; `Nothing` if one part is `Nothing`. */
  def and(filters: List[Filter]): Filter =
    val parts = filters.filterNot(_ == All)
    if parts.contains(Nothing) then Nothing
    else
      parts match
        case Nil         => All
        case only :: Nil => only
        case _           => And(parts)

  /** `Or` without the trivially false parts; `All` if one part is `All`. */
  def or(filters: List[Filter]): Filter =
    val parts = filters.filterNot(_ == Nothing).distinct
    if parts.contains(All) then All
    else
      parts match
        case Nil         => Nothing
        case only :: Nil => only
        case _           => Or(parts)

/** The permission filter of the search (DMS-52, ADR 0029): which indexed versions a subject may read, as a
  * filter of the query, so that hits, hit count, facet counts and snippets only ever cover readable versions.
  *
  * It mirrors [[dms.authz.Policy.decide]] for `ReadPublished` and `ReadDocument` on versions in an indexed
  * status (Published, ReviewDue, Superseded), which all grant reading to the same callers:
  *
  *   1. tenant: the index entry's tenant is the subject's tenant;
  *   1. scope: an assignment that is active now, is not `TenantAdmin` (administrators need a business role to
  *      read content) and whose organizational unit (if any) equals the version's and whose class (if any) is
  *      on the version's class path;
  *   1. confidentiality: the version is `Public` or `Internal`, or the subject is its author or owner, or one
  *      of the subject's groups is allowed.
  *
  * Role assignments and groups are read when the query is made, so changing them takes effect on the next
  * search; the attributes of the version come from the index.
  */
object AccessFilter:
  import Filter.*

  object Fields:
    val Tenant             = "tenantId"
    val OrganizationalUnit = "organizationalUnit"
    val ClassPath          = "classPath"
    val Confidentiality    = "confidentiality"
    val Author             = "authorId"
    val Owner              = "ownerId"
    val AllowedGroups      = "allowedGroups"

  /** Confidentiality levels everyone with a role in scope may read. */
  val OpenLevels: Set[String] = Set(Confidentiality.Public.toString, Confidentiality.Internal.toString)

  def forSubject(subject: Subject, now: Instant): Filter =
    val scopes = subject.assignments.toList
      .filter(a => a.isActiveAt(now) && a.role != Role.TenantAdmin)
      .map(a =>
        and(
          a.organizationalUnit.map(is(Fields.OrganizationalUnit, _)).toList ++
            a.documentClass.map(is(Fields.ClassPath, _)).toList
        )
      )
    val confidentiality = or(
      List(
        anyOf(Fields.Confidentiality, OpenLevels),
        is(Fields.Author, subject.userId.value),
        is(Fields.Owner, subject.userId.value)
      ) ++ Option.when(subject.groups.nonEmpty)(anyOf(Fields.AllowedGroups, subject.groups)).toList
    )
    and(List(is(Fields.Tenant, subject.tenantId.value.toString), or(scopes), confidentiality))

  /** The access attributes of a document version, from the resource [[dms.authz.Policy]] decides on. */
  def attributes(resource: Resource): AccessAttributes =
    AccessAttributes(
      resource.organizationalUnit,
      resource.confidentiality.toString,
      resource.authorId.getOrElse(UserId("")),
      resource.ownerId,
      resource.allowedGroups
    )

  /** The keyword fields of an index entry the filter reads. */
  def fields(tenant: String, classPath: List[String], access: AccessAttributes): Map[String, Set[String]] =
    Map(
      Fields.Tenant             -> Set(tenant),
      Fields.OrganizationalUnit -> access.organizationalUnit.toSet,
      Fields.ClassPath          -> classPath.toSet,
      Fields.Confidentiality    -> Set(access.confidentiality),
      Fields.Author             -> Set(access.authorId.value).filter(_.nonEmpty),
      Fields.Owner              -> access.ownerId.map(_.value).toSet,
      Fields.AllowedGroups      -> access.allowedGroups
    )
