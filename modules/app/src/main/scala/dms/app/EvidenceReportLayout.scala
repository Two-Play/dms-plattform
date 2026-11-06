package dms.app

import dms.rendering.{ArchiveStandard, PdfVariant}
import dms.rendering.core.{PdfJob, RenditionLayout}
import dms.templates.core.HtmlRenderer.escape

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}

/** The PDF/A form of an evidence report (DMS-57, ADR 0034): a stand-alone HTML page printed by the rendering
  * worker (ADR 0025). Pure: it shows the stored payload and nothing else, and every date comes from it, so
  * the same report always prints the same PDF. Every page names the report id and the SHA-256 of the JSON
  * payload, which is what a verifier checks.
  */
object EvidenceReportLayout:

  /** Version of the layout; bump it when the output changes. */
  val Version: String = "evidence-report-layout-2"

  private val time = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  def at(i: Instant): String = time.format(i)

  /** The print job of a stored report. The PDF file identifier is derived from the checksum. */
  def job(report: EvidenceReportPayload, sha256: String): PdfJob =
    PdfJob(
      html(report, sha256),
      PdfVariant.Archive(ArchiveStandard.default),
      sha256.take(32),
      Map("documentId" -> report.document.id.toString)
    )

  def footer(report: EvidenceReportPayload, sha256: String): String =
    s"Evidence report ${report.reportId} · created ${at(report.createdAt)} by ${person(report)(report.createdBy)}\nSHA-256 (JSON payload): $sha256"

  /** How the report shows a person (ADR 0077): the display name recorded in it, with the user id if two
    * people share a name, or the user id for someone without a name. Reports of format 1 hold no names.
    */
  private[app] def person(r: EvidenceReportPayload): String => String =
    val names  = r.persons.getOrElse(Map.empty)
    val shared = names.values.groupBy(identity).collect { case (name, all) if all.size > 1 => name }.toSet
    id => names.get(id).fold(id)(name => if shared(name) then s"$name ($id)" else name)

  def html(r: EvidenceReportPayload, sha256: String): String =
    val created = DateTimeFormatter.ISO_INSTANT.format(r.createdAt.truncatedTo(ChronoUnit.SECONDS))
    val margin = "font-family: \"Noto Sans\", sans-serif; font-size: 7pt; color: #333; white-space: pre-wrap;"
    val out    = StringBuilder(32768)
    val who    = person(r)
    def add(parts: String*): Unit = parts.foreach(p => out.append(p): Unit)
    add(
      "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n",
      s"<title>${escape(s"Evidence report: ${r.document.title}")}</title>\n",
      s"""<meta name="keywords" content="${escape(r.document.id.toString)}">\n""",
      s"""<meta name="keywords" content="${escape("sha256:" + sha256)}">\n""",
      s"""<meta name="generator" content="${escape("DMS " + Version)}">\n""",
      s"""<meta name="dcterms.created" content="$created">\n""",
      s"""<meta name="dcterms.modified" content="$created">\n""",
      "<style>\n",
      s"""@page { size: A4; margin: 20mm 15mm 24mm 15mm;
         |  @top-left { content: ${RenditionLayout.css(
          "Evidence report · " + r.document.title
        )}; width: 70%; vertical-align: bottom; padding-bottom: 2mm; border-bottom: 0.5pt solid #888; $margin }
         |  @top-right { content: ${RenditionLayout.css(
          "Document " + r.document.id
        )}; width: 30%; text-align: right; vertical-align: bottom; padding-bottom: 2mm; border-bottom: 0.5pt solid #888; $margin }
         |  @bottom-left { content: ${RenditionLayout.css(
          footer(r, sha256)
        )}; width: 80%; vertical-align: top; padding-top: 2mm; border-top: 0.5pt solid #888; $margin }
         |  @bottom-right { content: "Page " counter(page) " of " counter(pages); width: 20%; text-align: right;
         |    vertical-align: top; padding-top: 2mm; border-top: 0.5pt solid #888; $margin }
         |}
         |body { font-family: "Noto Sans", sans-serif; font-size: 8.5pt; color: #111; }
         |h1 { font-size: 15pt; margin: 0 0 3mm 0; }
         |h2 { font-size: 11.5pt; margin: 6mm 0 2mm 0; border-bottom: 0.5pt solid #888; }
         |h3 { font-size: 9.5pt; margin: 4mm 0 1.5mm 0; }
         |table { border-collapse: collapse; width: 100%; margin-bottom: 2mm; table-layout: fixed; }
         |th, td { border: 0.4pt solid #aaa; padding: 1mm 1.5mm; text-align: left; vertical-align: top;
         |  overflow-wrap: anywhere; }
         |th { background: #eef1f5; font-weight: bold; }
         |table.facts th { width: 32%; }
         |.mono { font-size: 7pt; }
         |.status-Intact { color: #146c2e; font-weight: bold; }
         |.status-Violated { color: #b42318; font-weight: bold; }
         |.status-Incomplete { color: #8a5a00; font-weight: bold; }
         |""".stripMargin,
      "</style>\n</head>\n<body>\n"
    )

    add(s"<h1>Evidence report</h1>\n")
    facts(
      out,
      List(
        "Document"       -> s"${r.document.title} (${r.document.id})",
        "Document class" -> r.document.classPath.mkString(" › "),
        "Author / owner" -> s"${who(r.document.authorId)} / ${r.document.ownerId.fold("–")(who)}",
        "Created"        -> at(r.document.createdAt),
        "Template"       -> s"${r.document.templateId} version ${r.document.templateVersion}",
        "Report id"      -> r.reportId.toString,
        "Report created" -> s"${at(r.createdAt)} by ${who(r.createdBy)}",
        "Report format"  -> r.format,
        "SHA-256 of the JSON payload" -> sha256,
        "Integrity status"            -> r.integrity.status
      ),
      statusRow = Some(r.integrity.status)
    )

    add("<h2>Integrity</h2>\n")
    val c = r.integrity.auditChain
    facts(
      out,
      List(
        "Checked at"  -> at(r.integrity.checkedAt),
        "Audit chain" -> (if !c.intact then "violated"
                          else
                            c.trustedPrefix.fold(s"intact, ${c.entriesChecked} entries verified")(p =>
                              s"intact, ${c.entriesChecked} entries after position ${p.untilSeq} verified"
                            )),
        "Chain head" -> s"${c.headSeq.fold("–")(_.toString)} ${c.headHash.getOrElse("")}",
        "Violation"  -> c.violation.fold("none")(v => s"position ${v.chainSeq}: ${v.kind} (${v.detail})")
      ) ++ c.trustedPrefix.toList.map(p =>
        "Verified prefix" ->
          s"up to anchored position ${p.untilSeq} (${p.hash}); whole chain last verified ${at(p.verifiedAt)}"
      ) ++ List(
        "Document entries" -> s"${c.documentEntries}, verified: ${yesNo(c.documentEntriesVerified)}",
        "Timestamp anchor" ->
          (c.anchoredUntilSeq.fold("none")(s =>
            s"up to position $s at ${c.anchoredAt.fold("–")(at)}"
          ) + s"; document entries covered: ${yesNo(c.documentEntriesAnchored)}"),
        "Write-once checkpoint" ->
          (c.checkpointedUntilSeq.fold("none")(s => s"up to position $s") +
            s"; document entries covered: ${yesNo(c.documentEntriesCheckpointed)}" +
            c.checkpointProblem.fold("")(p => s"; $p"))
      )
    )
    table(
      out,
      List("Version", "Check", "Status", "Expected", "Detail"),
      r.integrity.checks.map(k =>
        List(k.version, k.check, k.status, k.expected.getOrElse(""), k.detail.getOrElse(""))
      ),
      mono = Set(3)
    )
    if r.integrity.findings.nonEmpty then
      add("<h3>Findings</h3>\n<ul>\n")
      r.integrity.findings.foreach(f => add("<li>", escape(f), "</li>\n"))
      add("</ul>\n")

    add("<h2>Version chain</h2>\n")
    table(
      out,
      List("Version", "Status", "Change", "Created", "Released", "Valid", "Rendition SHA-256"),
      r.versions.map(v =>
        List(
          v.version,
          v.status,
          v.changeKind + v.changeReason.fold("")(c => s": $c"),
          s"${at(v.createdAt)} by ${who(v.createdBy)}",
          v.releasedAt.fold("–")(at) + v.releasedBy.fold("")(b => s" by ${who(b)}"),
          v.validity.fold("never valid")(p => s"${at(p.from)} – ${p.until.fold("now")(at)}"),
          v.rendition.fold("–")(_.sha256)
        )
      ),
      mono = Set(6)
    )

    r.versions.foreach { v =>
      add(s"<h2>Version ${escape(v.version)}</h2>\n")
      facts(
        out,
        List(
          "Version id"      -> v.id.toString,
          "Content SHA-256" -> v.contentSha256,
          "Valid from"      -> v.validFrom.fold("–")(_.toString),
          "Published"       -> v.publishedAt.fold("–")(at),
          "Superseded by"   -> v.supersededBy.fold("–")(_.toString)
        ) ++ v.editors.toList.map(e => "Changed by" -> e.map(who).mkString(", ")) ++ v.withdrawal.toList
          .flatMap(w =>
            List(
              "Withdrawn, never valid" ->
                (at(w.at) + s" by ${who(w.by)}" + w.actingFor.fold("")(p => s" on behalf of ${who(p)}")),
              "Reason of the withdrawal" -> w.reason
            )
          )
      )
      v.rendition.filter(r => r.producedBy.isDefined || r.retention.isDefined).foreach { r =>
        add("<h3>Rendition</h3>\n")
        facts(
          out,
          r.producedBy.toList.flatMap(p =>
            List(
              "Produced by"  -> p.renderer,
              "Worker image" -> p.workerImage.getOrElse("–"),
              "Worker build" -> p.workerBuild.getOrElse("–"),
              "Validated"    -> s"${p.standard} (${p.validator})"
            )
          ) ++ r.retention.toList.map(l => "Object Lock" -> s"${l.mode} until ${at(l.retainUntil)}")
        )
      }
      v.destruction.foreach { d =>
        add("<h3>Destruction</h3>\n")
        facts(
          out,
          List(
            "Destroyed"         -> at(d.destroyedAt),
            "Proposed"          -> s"${at(d.proposedAt)} by ${who(d.proposedBy)}: ${d.proposalReason}",
            "Approved"          -> s"${at(d.approvedAt)} by ${who(d.approvedBy)}: ${d.approvalReason}",
            "Retention"         -> s"${d.retentionRule}, ended ${d.retentionEndedOn}",
            "Rendition SHA-256" -> d.renditionSha256.getOrElse("–"),
            "Approved content"  -> d.contentSha256.getOrElse("–")
          )
        )
        if d.files.nonEmpty then
          table(
            out,
            List("File", "Role", "SHA-256", "Bytes removed"),
            d.files.map(f =>
              List(f.name, f.role, f.sha256, if f.released then "yes" else "no (still referenced)")
            ),
            mono = Set(2)
          )
      }
      v.original.foreach { o =>
        add("<h3>Original file</h3>\n")
        table(
          out,
          List("Name", "Size (bytes)", "SHA-256", "Uploaded", "Virus scan"),
          List(
            List(o.name, o.size.toString, o.sha256, s"${at(o.addedAt)} by ${who(o.addedBy)}", o.scanStatus)
          ),
          mono = Set(2)
        )
      }
      v.attachments.filter(_.nonEmpty).foreach { attachments =>
        add("<h3>Attachments</h3>\n")
        table(
          out,
          List("Name", "Size (bytes)", "SHA-256", "Added", "Virus scan"),
          attachments.map(a =>
            List(a.name, a.size.toString, a.sha256, s"${at(a.addedAt)} by ${who(a.addedBy)}", a.scanStatus)
          ),
          mono = Set(2)
        )
      }
      if v.deviationRequests.nonEmpty then
        add("<h3>Deviation requests</h3>\n")
        table(
          out,
          List("Section", "Reason", "Requested", "Withdrawn"),
          v.deviationRequests.map(d =>
            List(
              d.sectionId,
              d.reason,
              s"${at(d.requestedAt)} by ${who(d.requestedBy)}",
              d.withdrawnAt.fold("–")(w => s"${at(w)} by ${d.withdrawnBy.fold("")(who)}")
            )
          )
        )
      v.workflow.foreach { w =>
        add(s"<h3>Review and approval (case ${escape(w.caseId.toString)}, ${escape(w.status)})</h3>\n")
        table(
          out,
          List("Round / step", "Person", "Decision", "Reason", "Time", "Signed in"),
          w.votes.map(vote =>
            List(
              s"${vote.round} / ${vote.step} (${vote.lane})",
              who(vote.voter) + vote.actingFor.fold("")(p => s" on behalf of ${who(p)}"),
              vote.decision + vote.invalidatedAt.fold("")(i => s" (invalidated ${at(i)})"),
              vote.reason.getOrElse("–"),
              at(vote.votedAt),
              vote.authenticatedAt.fold("–")(at)
            )
          )
        )
        w.approval.foreach(a =>
          facts(
            out,
            List(
              "Approved" -> s"${at(a.approvedAt)} by ${who(a.approvedBy)}${a.actingFor
                  .fold("")(p => s" on behalf of ${who(p)}")}",
              "Approved content SHA-256" -> a.contentSha256
            )
          )
        )
      }
      v.acknowledgements.foreach { a =>
        add("<h3>Acknowledgements</h3>\n")
        facts(
          out,
          List(
            "Rate" -> (s"${a.acknowledged} of ${a.audience} readers (${a.ratePercent.fold("n/a")(_ + " %")}), " +
              s"evaluated ${at(a.evaluatedAt)}; acknowledgements in total: ${a.acknowledgedTotal}")
          )
        )
        if a.entries.nonEmpty then
          table(
            out,
            List("Reader", "Opened", "Acknowledged"),
            a.entries.map(e => List(who(e.userId), at(e.openedAt), at(e.acknowledgedAt)))
          )
      }
      if v.reviews.nonEmpty then
        add("<h3>Periodic reviews</h3>\n")
        table(
          out,
          List("Due on", "Status", "Decided", "Next review", "Comment"),
          v.reviews.map(t =>
            List(
              t.dueOn.toString,
              reviewStatus(t, v.reviews),
              t.decidedAt.fold("–")(d => s"${at(d)} by ${t.decidedBy.fold("")(who)}"),
              t.nextDueOn.fold("–")(_.toString),
              t.comment.getOrElse("–")
            )
          )
        )
    }

    add("<h2>Legal holds</h2>\n")
    if r.legalHolds.isEmpty then add("<p>None.</p>\n")
    else
      table(
        out,
        List("Name", "Reason", "Set", "Released"),
        r.legalHolds.map(h =>
          List(
            h.name + h.matterReference.fold("")(m => s" ($m)"),
            h.reason,
            s"${at(h.createdAt)} by ${who(h.createdBy)}",
            h.documentRemovedAt.fold(
              h.releasedAt.fold("active")(t =>
                s"${at(t)} by ${h.releasedBy.fold("")(who)}: ${h.releaseReason.getOrElse("")}"
              )
            )(t =>
              s"document removed ${at(t)} by ${h.documentRemovedBy.fold("")(who)}: " +
                h.documentRemovalReason.getOrElse("")
            )
          )
        )
      )

    add("<h2>Audit trail of the document</h2>\n")
    table(
      out,
      List("#", "Time", "Actor", "Action", "Version", "Reason", "Entry hash"),
      r.auditTrail.map(e =>
        List(
          e.chainSeq.toString,
          at(e.occurredAt),
          who(e.actorId) + e.onBehalfOf.fold("")(p => s" on behalf of ${who(p)}") +
            s" (${e.actorKind}, ${e.channel})",
          e.action,
          e.objectVersion.getOrElse("–"),
          e.reason.getOrElse("–"),
          e.hash
        )
      ),
      mono = Set(6)
    )

    r.persons.filter(_.nonEmpty).foreach { names =>
      add("<h2>Persons</h2>\n")
      table(
        out,
        List("Display name when the report was created", "User id"),
        names.toList.sortBy((id, name) => (name, id)).map((id, name) => List(name, id))
      )
    }

    add("<h2>Notes and verification</h2>\n<ul>\n")
    r.notes.foreach(n => add("<li>", escape(n), "</li>\n"))
    add(
      "<li>",
      escape(
        s"Verification: download the JSON form of this report (GET /api/v1/documents/${r.document.id}/evidence-reports/" +
          s"${r.reportId}) and compare its SHA-256 with the value above; it is also recorded in the audit trail " +
          "(action document.evidence-report-created) and in the report registry."
      ),
      "</li>\n</ul>\n"
    )
    add("</body>\n</html>\n")
    out.toString

  /** The status of a review task, naming the decided task it opened again (ADR 0061). */
  private[app] def reviewStatus(task: EvidenceReview, all: List[EvidenceReview]): String =
    task.reopens
      .flatMap(id => all.find(_.taskId == id))
      .flatMap(_.decidedAt)
      .fold(task.status)(d => s"${task.status} (reopens the task decided ${at(d)})")

  private def yesNo(b: Boolean): String = if b then "yes" else "no"

  private def facts(
      out: StringBuilder,
      rows: List[(String, String)],
      statusRow: Option[String] = None
  ): Unit =
    out.append("<table class=\"facts\">\n")
    rows.foreach { (k, v) =>
      val cls =
        statusRow.filter(_ => k == "Integrity status").fold("")(s => s""" class="status-${escape(s)}"""")
      out.append(s"<tr><th>${escape(k)}</th><td$cls>${escape(v)}</td></tr>\n")
    }
    out.append("</table>\n"): Unit

  private def table(
      out: StringBuilder,
      header: List[String],
      rows: List[List[String]],
      mono: Set[Int] = Set.empty
  ): Unit =
    if rows.isEmpty then out.append("<p>None.</p>\n"): Unit
    else
      out.append("<table>\n<tr>")
      header.foreach(h => out.append(s"<th>${escape(h)}</th>"))
      out.append("</tr>\n")
      rows.foreach { row =>
        out.append("<tr>")
        row.zipWithIndex.foreach((cell, i) =>
          out.append(if mono.contains(i) then s"""<td class="mono">${escape(cell)}</td>"""
          else s"<td>${escape(cell)}</td>")
        )
        out.append("</tr>\n")
      }
      out.append("</table>\n"): Unit
