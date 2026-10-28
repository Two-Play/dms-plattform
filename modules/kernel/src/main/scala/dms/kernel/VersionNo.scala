package dms.kernel

/** Version number per business concept 5.2: major = change of content, minor = editorial change. */
final case class VersionNo(major: Int, minor: Int):
  require(major >= 0 && minor >= 0, "version numbers must not be negative")

  def nextMajor: VersionNo = VersionNo(major + 1, 0)
  def nextMinor: VersionNo = VersionNo(major, minor + 1)
  def show: String         = s"$major.$minor"

object VersionNo:
  val Initial: VersionNo = VersionNo(1, 0)

  given Ordering[VersionNo] = Ordering.by(v => (v.major, v.minor))

  def parse(s: String): Option[VersionNo] = s.split('.') match
    case Array(ma, mi) =>
      (ma.toIntOption, mi.toIntOption) match
        case (Some(a), Some(b)) if a >= 0 && b >= 0 => Some(VersionNo(a, b))
        case _                                      => None
    case _ => None
