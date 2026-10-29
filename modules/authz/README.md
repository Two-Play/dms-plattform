# Module `authz`

Authorization (ADR 0018, business concept 2.3): `Policy.decide` is a pure, deny-by-default decision over roles and attributes (tenant, organizational unit, document class, confidentiality, status, ownership, review assignment, segregation of duties). `RoleAssignments` stores scoped, time-limited role assignments in `dms.role_assignment` (migration V5, RLS). `Authorizer.check` applies the policy for the signed-in caller and logs every denial with its reason.

`PolicySpec` contains one test per cell of the permission matrix.

Templates (business concept 4.1): `EditTemplates` covers creating and changing templates; `ReleaseTemplate` additionally requires that the template designer neither created (`Resource.authorId`) nor changed (`Resource.contributors`) the version (four-eyes principle); `ReadTemplateCatalog` lets every business role see released templates.

Documents (business concept 5.1, DMS-37): `ReadDocument` reads a version in any status along the visibility column of the status model: drafts for authors and owners, review and approval additionally for assigned reviewers and approvers, approved versions also for their approver, published versions like `ReadPublished`, superseded versions for every business role, withdrawn and archived versions for owners, records managers and auditors. `CreateFromTemplate` and `EditDraft` (also used for submitting a draft) are checked on a `Resource` built from the document: class path of its profile, organizational unit, confidentiality, status, author of the version and owner.

Periodic review (business concept 6.2, DMS-55, ADR 0030): `Recertify` (confirming a valid version without a change) is granted in status `Published` or `ReviewDue` to holders of the owner role who are the document's owner (`Resource.ownerId`); withdrawing a valid version uses `Withdraw` (owner or approver role).

Workflows (business concept 6.1, DMS-40): `Review` (casting a review vote) is granted in status `InReview` to participants the workflow assigned to the task (`Resource.assignedParticipants`) who hold the author, owner, reviewer or approver role; approval votes use `Approve`. `RoleAssignments.holders` lists the holders of a role, from which the workflow resolves participants.

Deputies (DMS-49, ADR 0028 and addendum to ADR 0018): `Authorizer.onBehalfOf(subject, principal, roles)` extends a deputy's subject by the principal's assignments of the given roles. The application uses it only for the document of a workflow task the deputy may complete during an active deputyship; `Policy` itself is unchanged.

Document resources and search (DMS-51/52, ADR 0029 and addendum to ADR 0018): `DocumentResource(tenant, document, classPath, version, participants)` builds the `Resource` of a version from the document and its field values (`organizationalUnit`, `confidentiality`, optional `allowedGroups`); the document API and the search indexer use it, and the search's permission filter mirrors `Policy` on these attributes. `ConfigurationArea.Search` covers rebuilding the search index.

Legal holds (DMS-56, ADR 0031 and addendum to ADR 0018): `ManageRetention` (records managers, matrix row "Retention / legal hold") covers legal holds. Hold-level operations check it on the tenant resource (records manager for the whole tenant), each document put under a hold on its `DocumentResource`.

Single module without an `api`/`core` split.

Document numbers (DMS-113, ADR 0040): `AssignDocumentNumber` (manual document number with reason) is granted on a document (any status but `Destroyed`) to records managers in scope and to holders of the owner role who are the document's owner; tenant administrators manage number ranges with `Configure` on `ConfigurationArea.NumberRanges`.
