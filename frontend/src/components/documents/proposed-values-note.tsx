import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { UserName } from '@/components/shared/user-name'
import { t } from '@/i18n'

/** Versions on their way into force: what they name is still a proposal (ADR 0074). */
const PROPOSING = new Set(['Draft', 'InReview', 'InApproval', 'Approved'])

/**
 * The title and owner a version proposes where they differ from the document's. The heading shows what is in
 * force; both change only when this version is published, and until then the person named has no right.
 */
export function ProposedValuesNote({
  document,
  version,
}: {
  document: DocumentView
  version: DocumentVersionView
}) {
  if (!PROPOSING.has(version.status)) return null
  const title = version.values.title?.value
  const owner = version.values.owner?.value
  const proposedTitle =
    title?.type === 'Text' && title.value.trim() !== document.title ? title.value.trim() : undefined
  const proposedOwner =
    owner?.type === 'PersonRef' && owner.userId !== document.ownerId ? owner.userId : undefined
  if (proposedTitle === undefined && proposedOwner === undefined) return null
  const [before, after] = t('docs.proposed.owner', { name: '{name}' }).split('{name}')
  return (
    <div className="rounded-md border bg-muted/50 p-3 text-sm" data-testid="proposed-values">
      <p>{t('docs.proposed.hint')}</p>
      <ul className="mt-1 list-inside list-disc">
        {proposedTitle !== undefined && (
          <li>{t('docs.proposed.title', { title: proposedTitle })}</li>
        )}
        {proposedOwner !== undefined && (
          <li>
            {before}
            <UserName id={proposedOwner} />
            {after}
          </li>
        )}
      </ul>
    </div>
  )
}
