package dms.db

import com.augustnagro.magnum.DbCodec
import org.postgresql.util.PGobject

import java.sql.{PreparedStatement, ResultSet, Types}

/** JSON text stored as PostgreSQL `jsonb`.
  *
  * An opaque type on purpose: for a case class, Magnum's `sql"..."` interpolator derives a codec that writes
  * varchar instead of using this one (finding of spike DMS-21).
  */
opaque type Jsonb = String

object Jsonb:
  def apply(json: String): Jsonb = json
  val empty: Jsonb               = "{}"

  extension (j: Jsonb) def value: String = j

  given DbCodec[Jsonb] with
    def queryRepr: String                                            = "?"
    val cols: IArray[Int]                                            = IArray(Types.OTHER)
    def readSingle(rs: ResultSet, pos: Int): Jsonb                   = rs.getString(pos)
    def writeSingle(j: Jsonb, ps: PreparedStatement, pos: Int): Unit =
      val o = PGobject()
      o.setType("jsonb")
      o.setValue(j)
      ps.setObject(pos, o)
