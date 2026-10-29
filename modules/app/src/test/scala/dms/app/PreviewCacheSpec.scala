package dms.app

import zio.*
import zio.test.*

import java.util.UUID

/** Converted previews are kept within a byte budget, least recently used first out (DMS-121). */
object PreviewCacheSpec extends ZIOSpecDefault:

  private def key(n: Int)    = PreviewCache.Key(UUID(0L, n.toLong), s"sha-$n")
  private def pdf(size: Int) = Chunk.fill(size)(1.toByte)

  def spec = suite("Preview cache")(
    test("entries are found by version and input; the least recently used ones are dropped") {
      val cache = PreviewCache(100)
      for
        _     <- cache.put(key(1), pdf(25))
        _     <- cache.put(key(2), pdf(25))
        _     <- cache.put(key(3), pdf(25))
        _     <- cache.get(key(1)) // 1 is now used more recently than 2
        _     <- cache.put(key(4), pdf(25))
        _     <- cache.put(key(5), pdf(25))
        one   <- cache.get(key(1))
        two   <- cache.get(key(2))
        five  <- cache.get(key(5))
        other <- cache.get(PreviewCache.Key(UUID(0L, 1L), "sha-other"))
        usage <- cache.usage
      yield assertTrue(
        one.isDefined,
        two.isEmpty,
        five.contains(pdf(25)),
        other.isEmpty,
        usage == (4, 100L)
      )
    },
    test("entries above a quarter of the budget are not kept; a zero budget keeps nothing") {
      val small = PreviewCache(100)
      val off   = PreviewCache(0)
      for
        _     <- small.put(key(1), pdf(26))
        _     <- off.put(key(1), pdf(1))
        big   <- small.get(key(1))
        none  <- off.get(key(1))
        usage <- off.usage
      yield assertTrue(big.isEmpty, none.isEmpty, usage == (0, 0L))
    }
  )
