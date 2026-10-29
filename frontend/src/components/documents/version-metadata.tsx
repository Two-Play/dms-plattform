import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useApiClient } from '@/api/context'
import { documentWorkflowQuery } from '@/api/documents'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { templateFieldsQuery, type MetadataValue } from '@/api/templates'
import { UserName } from '@/components/shared/user-name'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { t, tOptional } from '@/i18n'
import { formatDate, formatPlainDate } from '@/i18n/format'
import { displayValue } from './draft-values'
import { StatusBadge } from './status-badge'

/**
 * Keys of the values the metadata panel shows in its own rows; the "further details" list leaves them
 * out. `documentNumber` is the core field of DMS-113, shown when a version has it.
 */
const PANEL_KEYS = new Set([
  'title',
  'documentId',
  'documentNumber',
  'documentClass',
  'version',
  'status',
  'owner',
  'organizationalUnit',
  'validFrom',
  'validUntil',
  'confidentiality',
  'template',
  'templateVersion',
  'renditionChecksum',
])

/**
 * The revision index of a version (ADR 0039): its version number major.minor, e.g. `1.1`. While a
 * version is open its label carries the working state (`1.1-draft.3`); the index is the part before it.
 */
function revisionIndex(label: string): string {
  return label.split('-')[0] ?? label
}

function valueOf(version: DocumentVersionView, key: string): MetadataValue | undefined {
  return version.values[key]?.value
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="contents">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  )
}

function NotSet() {
  return <span className="text-muted-foreground">{t('docs.meta.notSet')}</span>
}

/**
 * The metadata of a version (DMS-112): status, version and revision index, document number, author,
 * owner, issuing body (the organizational unit), class, validity, confidentiality, approval (who,
 * when, on behalf of whom) and template; then the further field values of the template.
 */
export function VersionMetadata({
  document,
  version,
}: {
  document: DocumentView
  version: DocumentVersionView
}) {
  const client = useApiClient()
  const fields = useQuery(templateFieldsQuery(client, version.templateId, version.templateVersion))
  const workflow = useQuery(documentWorkflowQuery(client, document.id))
  const approval = workflow.data?.find((c) => c.approval?.versionId === version.id)?.approval

  const owner = valueOf(version, 'owner')
  const ownerId = owner?.type === 'PersonRef' ? owner.userId : document.ownerId
  const number = valueOf(version, 'documentNumber')
  const unit = valueOf(version, 'organizationalUnit')
  const validFromValue = valueOf(version, 'validFrom')
  const validFrom =
    version.validFrom ?? (validFromValue?.type === 'Date' ? validFromValue.value : undefined)
  const validUntil = valueOf(version, 'validUntil')
  const level = valueOf(version, 'confidentiality')
  const levelCode = level?.type === 'Selection' ? level.code : undefined
  const index = revisionIndex(version.version)
  const open = version.version !== index

  const approvedBy = approval?.approvedBy ?? version.releasedBy
  const approvedAt = approval?.approvedAt ?? version.releasedAt
  const labels = new Map((fields.data?.fields ?? []).map((f) => [f.key, f.label]))
  const further = Object.entries(version.values).filter(
    ([key]) => labels.has(key) && !PANEL_KEYS.has(key),
  )

  return (
    <Card data-testid="version-metadata">
      <CardHeader>
        <CardTitle>{t('docs.meta.title')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        <dl className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-4 gap-y-2 text-sm">
          <Row label={t('docs.meta.status')}>
            <StatusBadge status={version.status} />
          </Row>
          <Row label={t('docs.meta.index')}>
            {t('docs.meta.indexValue', { index })}
            {open && (
              <span className="text-muted-foreground">
                {' '}
                ({t('docs.meta.workingState', { state: version.workingState })})
              </span>
            )}
          </Row>
          {number && <Row label={t('docs.meta.documentNumber')}>{displayValue(number)}</Row>}
          <Row label={t('docs.meta.author')}>
            <UserName id={version.createdBy} />
          </Row>
          <Row label={t('docs.meta.owner')}>{ownerId ? <UserName id={ownerId} /> : <NotSet />}</Row>
          <Row label={t('docs.meta.issuingBody')}>{unit ? displayValue(unit) : <NotSet />}</Row>
          <Row label={t('docs.meta.documentClass')}>
            {document.classPath && document.classPath.length > 0 ? (
              document.classPath.join(' › ')
            ) : (
              <NotSet />
            )}
          </Row>
          <Row label={t('docs.meta.validFrom')}>
            {validFrom ? formatPlainDate(validFrom) : <NotSet />}
          </Row>
          <Row label={t('docs.meta.validUntil')}>
            {validUntil ? displayValue(validUntil) : <NotSet />}
          </Row>
          <Row label={t('docs.meta.confidentiality')}>
            {levelCode ? (tOptional(`docs.meta.levels.${levelCode}`) ?? levelCode) : <NotSet />}
          </Row>
          <Row label={t('docs.meta.approval')}>
            {approvedBy && approvedAt ? (
              <span data-testid="approval">
                <UserName id={approvedBy} />{' '}
                {t('docs.meta.approvedOn', { date: formatDate(approvedAt) })}
                {approval?.actingFor && (
                  <>
                    {' '}
                    ({t('docs.meta.onBehalfOf')} <UserName id={approval.actingFor} />)
                  </>
                )}
              </span>
            ) : (
              <span className="text-muted-foreground">{t('docs.meta.notApproved')}</span>
            )}
          </Row>
          {version.publishedAt && (
            <Row label={t('docs.meta.published')}>{formatDate(version.publishedAt)}</Row>
          )}
          <Row label={t('docs.meta.template')}>
            {t('docs.meta.templateValue', {
              name: fields.data?.name ?? version.templateId,
              version: version.templateVersion,
            })}
          </Row>
        </dl>
        {further.length > 0 && (
          <div className="space-y-2">
            <h3 className="font-heading text-sm font-semibold">{t('docs.meta.more')}</h3>
            <dl className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-4 gap-y-2 text-sm">
              {further.map(([key, field]) => (
                <Row key={key} label={labels.get(key) ?? key}>
                  {field.value.type === 'PersonRef' ? (
                    <UserName id={field.value.userId} />
                  ) : (
                    displayValue(field.value)
                  )}
                </Row>
              ))}
            </dl>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
