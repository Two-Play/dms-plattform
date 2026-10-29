package dms.app

import zio.*

import java.util.UUID

/** Converted PDFs of file document versions kept in memory (DMS-121, ADR 0044), so that reading or printing
  * an unchanged draft does not run the conversion worker again. Bounded by the total size of the PDFs; the
  * least recently used entries are dropped first. Entries above a quarter of the budget are not kept. Per
  * instance and lost on restart: the cache only saves conversions, every entry can be computed again.
  *
  * @param maxBytes
  *   budget for all PDFs together; 0 disables the cache
  */
final class PreviewCache(maxBytes: Long):
  import PreviewCache.Key

  private val entries = java.util.LinkedHashMap[Key, Chunk[Byte]](16, 0.75f, true)
  private var total   = 0L

  def get(key: Key): UIO[Option[Chunk[Byte]]] =
    ZIO.succeed(synchronized(Option(entries.get(key))))

  def put(key: Key, pdf: Chunk[Byte]): UIO[Unit] =
    ZIO.succeed(synchronized {
      if pdf.length.toLong <= maxBytes / 4 then
        Option(entries.put(key, pdf)).foreach(previous => total -= previous.length)
        total += pdf.length
        val eldest = entries.entrySet.iterator
        while total > maxBytes && eldest.hasNext do
          total -= eldest.next.getValue.length
          eldest.remove()
    })

  /** Number of entries and their total size in bytes. */
  def usage: UIO[(Int, Long)] = ZIO.succeed(synchronized((entries.size, total)))

object PreviewCache:

  /** A converted PDF: of which version, from which converted bytes (SHA-256 of the filled original). */
  final case class Key(versionId: UUID, inputSha256: String)

  /** 64 MiB. */
  val DefaultBytes: Long = 64L * 1024 * 1024
