# 0072: Rights on a document are decided on what stands for it, each action on what it reaches; a version out of force says so wherever it is handed out

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-155) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

DMS-155 collects the follow-ups of DMS-145 (ADR 0059, ADR 0070). Each was checked against the code first.

1. **Rights on the latest version.** `RetentionApi`, `LegalHoldApi` and `EvidenceReportApi` build the `Resource` from `versions.last`. ADR 0059 names the harm: "a never-valid version with wrong metadata stays the latest one, which can lock out records managers scoped to the valid version". Still true, but the check found more:
   - **Scope only matters for evidence reports.** Every retention and hold endpoint first asks `checkedSubject`, the right on the tenant resource. A records manager scoped to a unit fails it, so for these endpoints only *confidentiality* (the groups, author and owner of a version) can change the outcome. The evidence report has no such gate: scoped auditors and records managers reach it, so unit and class count there.
   - **The latest version also lets people in.** An author can give a draft `Internal` while the valid version is `Confidential`. Everyone then passes, and the report shows the valid version. The same holds for a confidential version followed by an internal one.
   - **A plain "valid version instead of latest" would give access away.** A confidential draft would no longer protect anything: the report shows it, and the document's title follows the draft (`DocumentService`, the title of an update). Destruction is worse: it destroys a given version, and neither the latest nor the valid version says anything about that one.
   - **Several versions can be valid**, one per validity scope, each with its own metadata. "The valid version" is not one version.
2. **`canWithdraw` in the failure notice.** `PlatformNotificationEvents` decides on the recipient's own roles. Still true: a deputy from another scope with a standing vote is told that others can withdraw, and `canRead` says they cannot see the page, though they can do both (ADR 0070, decisions 3 to 5).
3. **Filled files of versions out of force say "Freigegeben".** Still true: on-demand fills always know the approval, and `WordFilling` prints "Approved" for it. ADR 0070 left open what a filled file records.
4. **A filled file without the field `status` carries no marking.** Still true: filling only fills fields. A file without fields is not touched at all.
5. **`GET .../html`, the archive download and the file preview have no "never valid" marking.** Still true, and ADR 0070, decision 12 accepted it for the last two.

Given: `Policy.decide` asks for a role in scope, then `clearedFor`, then the action. `ManageRetention` and `GenerateEvidenceReport` do not look at the status. `Destroy` needs `Archived`, so it is decided as archived. A destroyed version has no field values left. The archive rendition is immutable, and its SHA-256 is sent as `ETag` and `Repr-Digest` (ADR 0021, 0059). `PdfComposer.stamp` marks what is handed out; copies of file documents use it (ADR 0070, decision 9). Filled files are not stored.

## Decision

### Which version decides

1. **Governing versions.** `Versioning.governing`: all valid versions and whatever is on its way into force (open or approved). If there is none, the latest version that was in force, else the latest version. A version that ended without taking effect, and a discarded draft, have no say.
2. **Every governing version must permit it.** Not any one of them. A version cannot widen what another one restricts, so an author cannot open the document with a draft. For a document whose latest version is open, waiting or valid this can only take away what the latest version gave.
3. **The answer differs per endpoint**, by what the action reaches:

| Endpoints | What the action reaches | Decided on |
|---|---|---|
| Document retention rule (`PUT`, `canChange`); titles in lists, proposals and holds | The document | Governing versions |
| Legal holds: create, add, remove, preview, titles | The document, all versions, without showing them | Governing versions |
| Proposing and approving a destruction | The versions named in the proposal | Governing versions **and** each named version |
| Evidence report: create, JSON, PDF | The document, and it shows every version with votes, reasons, persons and file names | Role and scope on the governing versions; **clearance for every version** (`Authorizer.checkCleared`) |

4. **A version that never took effect in the evidence report.** Its unit and class do not count, which is the point of ADR 0059: such a version may be there because they are wrong. Its confidentiality counts: someone said this version needs protection, and the report shows it. A caller who is not cleared gets 403, as they did while it was the latest version.
5. **Conservative choices.** An open draft keeps a say, so a draft with other metadata still stops a records manager from setting a legal hold, as it did before. It can be corrected or discarded, a version that never took effect cannot. A stored report follows the same rule as a new one, so a later confidential version closes older reports too.

### Failure notices

6. **One decision.** `withdrawalRight` moves to `Delegation` and takes a subject and the role assignments. The dialog, the withdrawal and the notice all ask there. `canRead` also counts `Delegation.approvedAsDeputy`, and so does `PolicyNotificationDocuments`, so the notice names the version the deputy may read. After the deputyship all three say no. `Policy` does not change.

### Filled files

7. **What a filled file records.** The state of the version when the file is handed out, as soon as that state is not the one that was approved: "Abgelöst", "Außer Kraft", "Archiviert", "Zurückgezogen, nie gültig" (`VersionPrintLayout.outOfForce`). The fields `approvedAt` and `approvedBy` stay, the approval happened. Open, approved and valid versions keep their wording. The archive rendition was filled at approval and stays as it is.
8. **The marking of a file without the field.** The file name: "Vertrag (Zurückgezogen, nie gültig).docx", for Word, Excel and PowerPoint alike, and for every version out of force. The bytes stay as they are; see the alternatives. The name also reaches the browser: `attachmentName` reads `filename*`.

### Never valid, on the three remaining ways out

9. **`GET .../html`.** The fragment gets the banner and the watermark a draft has, with the text "Zurückgezogen, nie gültig", and `VersionHtmlView.neverValidMarking` says the same. `draftMarking` stays empty: it means "never approved". Only this endpoint does this; the copies and the Word export have their own marking.
10. **File preview.** `preview.pdf` stamps the marking and the identification on every page of what it sends, as a draft. The object in the archive is read, not changed, and nothing is stored.
11. **Archive download.** The bytes are not marked: they are the archived file, whose checksum the same response states, and evidence must be able to compare it. Only the file name says it: `<id>-<version>-never-valid.pdf`. A marking inside this file is unsound, not just expensive.
12. **The original of a file document** (`GET .../file`) stays as it is: it is what was uploaded, and no ticket asked for it.

No migration; V49 stays free. No new dependency.

## Alternatives considered

| Criterion (which version decides) | Chosen: governing versions, per action | Latest version (before) | Only the valid version | Every version |
|---|---|---|---|---|
| Never-valid version with wrong metadata locks out | No | Yes | No | Yes |
| A draft widens access to the document | No | Yes | No | No |
| A confidential draft loses its protection | No | No | Yes | No |
| Works with several validity scopes | Yes | Takes one by chance | Needs a rule anyway | Yes |
| Document without valid version | Defined | Yes | Needs a rule anyway | Yes |
| Old versions in another unit lock out | No | No | No | Yes: a document that moved could not be managed |

| Criterion (evidence report) | Chosen: refuse without clearance for every version | Decide on governing versions only | Leave out the sections of versions the caller is not cleared for |
|---|---|---|---|
| Nobody sees what they may not read | Yes | No | Yes |
| The report stays complete, one report per document state | Yes | Yes | No: two callers get two reports, and a gap in evidence |
| Auditor without clearance gets something | No | Yes | Yes |
| Format and registry unchanged | Yes | Yes | No |

| Criterion (destruction) | Chosen: document and named versions | Document only | Named versions only |
|---|---|---|---|
| Someone not cleared for a version destroys it | No | Yes | No |
| Latest never-valid version blocks destroying an older one | No | No | No |
| A confidential document's versions stay out of reach of those not cleared for it | Yes | Yes | No |
| Cost | One more check per version | None | One more check per version |

| Criterion (what a filled file records) | Chosen: the state at download, once it ended | The approval, always | The status, always ("Veröffentlicht") |
|---|---|---|---|
| A reader of the file learns it is out of force | Yes | No | Yes |
| Valid versions keep their wording | Yes | Yes | No: every existing file changes |
| Same wording as copies and the document page | Yes | No | Yes |
| The comparison of two filled versions | Shows the field as changed | Unchanged | Shows the field as changed |

| Criterion (marking without the field) | Chosen: file name | A paragraph or header in the file | Core property `contentStatus` | Nothing |
|---|---|---|---|---|
| Same for Word, Excel and PowerPoint | Yes | No: three ways, and Excel prints it only | Yes | Yes |
| Layout of the record stays | Yes | No | Yes | Yes |
| Seen without opening anything | Yes | No | No: Info pane only | No |
| Stale after the file is edited and uploaded as the next version | No | Yes: the new valid version would say "never valid" | Yes, the same | No |
| Survives renaming | No | Yes | Yes | Not relevant |

| Criterion (archive download) | Chosen: file name only | Stamp the PDF | Refuse the download | Leave it |
|---|---|---|---|---|
| Archive immutable, checksum verifiable | Yes | No: the response would not match its own digest | Yes | Yes |
| Evidence stays available | Yes | Only as a copy | No | Yes |
| A saved file says it | By its name | Yes | Not relevant | No |

## Consequences

### Positive

- A version stopped because of its metadata no longer stands between records managers or auditors and the document.
- Three openings are closed that the issue did not name: a draft that widens the document, a report showing versions the caller is not cleared for, and the destruction of a version by someone not cleared for it.
- Nobody gains through this change except in the two cases that are its purpose: a version that never took effect no longer decides, for the role and scope everywhere and for clearance where it is not shown.
- What a notice promises is what the page offers, because they ask the same function.
- A filled file, a fragment and a preview no longer look valid when they are not.

### Negative / Cost

- Access is taken away: an auditor not cleared for an old or never-valid confidential version gets no report of the document, also none stored earlier. They need a colleague in the access group, or a group membership. There is no partial report.
- A records manager not cleared for one valid version of a document with several validity scopes cannot manage the document, though they could when it was not the latest.
- A draft with stricter metadata still stops a legal hold, so an author can delay one until someone cleared acts. That was so before; the change does not make it worse, and the conservative reading of the issue keeps it.
- The destruction list shows titles by document, the proposal asks by version: an entry with a title can still be refused with 403 if the records manager is not cleared for that version.
- `Versioning.governing` looks at all versions of a document; that is in memory, and the documents were read anyway.
- The comparison of a superseded and a valid filled version shows the field `status` as changed.
- A file name is lost by renaming the file. A filled file without the field carries nothing inside, once renamed.
- Search text of Word originals follows the wording when a version is indexed again; only the field `status` differs.
- The fragment shows the draft banner's style for something that is not a draft. A style of its own was not worth a change of the shared stylesheet.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: a legal hold is protective, and it reaches nothing it shows. Whether it may be set past a stricter draft is a question for records management; the code keeps the old answer.
- Could: an evidence report with sections left out for callers not cleared for them, if auditors need one; needs a format decision.
- Could: the original (`GET .../file`) with the same file name as filled files.
- Could: `GET .../retention` without `version` shows the rule of the open or latest version, also one that never took effect; a display question, not a right.

## Reversibility

**high**: No data changes. `Versioning.governing`, the checks in the three APIs, the file names and the markings are code. `VersionHtmlView.neverValidMarking` is an added optional field. Going back to the latest version is one line per API, and would open what is closed here again.
