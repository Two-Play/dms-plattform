package dms.db

import java.sql.SQLException

/** Typed database errors, mapped from the SQLSTATE of the first SQLException in the cause chain. */
enum DbError:
  /** A tenant-scoped table was accessed without tenant context (SQLSTATE DMS01, see V2 migration). */
  case TenantContextMissing

  /** An append-only table rejected UPDATE or DELETE (trigger, SQLSTATE P0001). */
  case AppendOnlyViolation(message: String)

  /** Missing privilege or a row-level security WITH CHECK violation. PostgreSQL reports both as 42501. */
  case PermissionDenied(message: String)

  /** Unique constraint violation (23505). */
  case UniqueViolation(constraint: String, message: String)

  /** Database code was called outside [[Db.transact]]. */
  case NoTransaction

  case Other(sqlState: String, message: String)
  case Unexpected(cause: Throwable)

object DbError:
  def from(t: Throwable): DbError =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).collectFirst { case e: SQLException => e } match
      case Some(e) =>
        e.getSQLState match
          case "DMS01" => TenantContextMissing
          case "P0001" => AppendOnlyViolation(e.getMessage)
          case "42501" => PermissionDenied(e.getMessage)
          case "23505" => UniqueViolation(constraintName(e).getOrElse(""), e.getMessage)
          case state   => Other(Option(state).getOrElse(""), e.getMessage)
      case None => Unexpected(t)

  private def constraintName(e: SQLException): Option[String] = e match
    case pe: org.postgresql.util.PSQLException =>
      Option(pe.getServerErrorMessage).flatMap(m => Option(m.getConstraint))
    case _ => None
