import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentQuery } from '@/api/documents'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { useGroups } from '@/api/groups'
import { type DocumentLanguage, type MetadataValue } from '@/api/templates'
import { documentVersionsQuery } from '@/api/versions'
import { ErrorState } from '@/components/shared/error-state'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
  PageHeader,
} from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { t, tOptional } from '@/i18n'
import { allowedGroupsOf, confidentialityOf, isRestrictedLevel } from './access-groups'
import { LanguageSelect } from './document-workspace'
import { DocumentEditor } from './new-document-page'
import { LegalHoldBadge } from '@/components/legal-holds/legal-hold-badge'
import { StatusBadge } from './status-badge'
import { WorkflowPanel } from './workflow-panel'
import { AcknowledgementPanel } from './acknowledgement-panel'
import { AcknowledgementOverviewPanel } from './acknowledgement-overview-panel'
import { AttachmentsPanel } from './attachments-panel'
import { EvidenceReportAction } from './evidence-report-panel'
import { DocumentNumberAction } from './document-number-action'
import { VersionHistory } from './version-history'
import { PdfDownload } from './pdf-download'
import { VersionContent } from './version-content'
import { VersionMetadata } from './version-metadata'
import { FileContentPanel } from './file-content-panel'
import { NewVersionAction } from './new-version-action'
import { DiscardDraftAction } from './discard-draft-action'
import { ChangeSummaryPanel } from './change-summary-panel'

/** Whether the page shows the acknowledgement card for a version: valid and requiring it. */
function asksForAcknowledgement(version: DocumentVersionView): boolean {
  return (
    (version.status === 'Published' || version.status === 'ReviewDue') &&
    version.requiresAcknowledgement
  )
}

/**
 * Whether the page shows the change summary card (DMS-103): for a draft of a later version (to write it), or
 * for any version that has one. A first draft (1.0) has nothing to compare with.
 */
function showsChangeSummary(version: DocumentVersionView, editing: boolean): boolean {
  if (version.changeReason) return true
  return editing && !version.version.startsWith('1.0')
}

/** The language of the document if it has one the preview supports. */
function documentLanguage(document: DocumentView): DocumentLanguage {
  const value = document.current.values.language?.value
  return value?.type === 'Text' && value.value === 'de' ? 'de' : 'en'
}

/**
 * Who may see a confidential version besides its author and owner (DMS-101): the names of its access groups,
 * or a note that nobody else may until groups are named.
 */
export function AccessNote({ document }: { document: DocumentView }) {
  const values: Record<string, MetadataValue | undefined> = Object.fromEntries(
    Object.entries(document.current.values).map(([key, field]) => [key, field.value]),
  )
  const level = confidentialityOf(values)
  const restricted = isRestrictedLevel(level)
  const groups = useGroups(restricted)
  if (!restricted || level === undefined) return null
  const keys = allowedGroupsOf(values)
  const names = new Map((groups.data ?? []).map((g) => [g.key, g.name]))
  const listed = keys.map((k) => names.get(k) ?? k)
  return (
    <p className="text-sm" data-testid="access-note">
      <span className="font-medium">{tOptional(`docs.page.levels.${level}`) ?? level}:</span>{' '}
      {listed.length > 0
        ? t('docs.page.visibleToGroups', { groups: listed.join(', ') })
        : t('docs.page.visibleToOwners')}
    </p>
  )
}

/**
 * A document (DMS-90, DMS-112): its draft to continue editing and submit, or a version the caller may read
 * with its rendered content and metadata; the review and approval state and the version history. `versionId`
 * picks a version from the history; without it the page shows the current version.
 */
export function DocumentPage({
  documentId,
  versionId,
}: {
  documentId: string
  versionId?: string
}) {
  const client = useApiClient()
  const document = useQuery(documentQuery(client, documentId))
  const versions = useQuery({ ...documentVersionsQuery(client, documentId), enabled: !!versionId })
  const [language, setLanguage] = useState<DocumentLanguage | undefined>(undefined)

  const current = document.data?.current
  const picked = versionId ? versions.data?.find((v) => v.id === versionId) : undefined
  const shown = picked ?? current
  const showingOther = shown !== undefined && current !== undefined && shown.id !== current.id
  const editing = shown?.status === 'Draft' && !showingOther
  // A file document's content is its uploaded original (DMS-115), not structured content.
  const fileDocument = document.data?.kind === 'File'

  return (
    <div className="space-y-6">
      <PageHeader
        title={document.data?.title ?? t('common.document')}
        breadcrumbs={
          <Breadcrumbs>
            <Link to="/documents" className={breadcrumbLinkClass}>
              {t('docs.page.breadcrumb')}
            </Link>
            <BreadcrumbCurrent>{document.data?.title ?? t('common.document')}</BreadcrumbCurrent>
          </Breadcrumbs>
        }
        actions={
          document.data &&
          shown && (
            <>
              {editing && (
                <div className="w-48">
                  <LanguageSelect
                    value={language ?? documentLanguage(document.data)}
                    onChange={setLanguage}
                  />
                </div>
              )}
              <PdfDownload
                key={shown.id}
                documentId={document.data.id}
                version={shown}
                wordExport={!fileDocument}
              />
              <DocumentNumberAction
                documentId={document.data.id}
                documentNumber={document.data.documentNumber}
                canAssign={document.data.canAssignNumber}
              />
              <NewVersionAction document={document.data} />
              {!showingOther && <DiscardDraftAction document={document.data} />}
              <EvidenceReportAction documentId={document.data.id} />
            </>
          )
        }
      >
        {document.data && shown && (
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <StatusBadge status={shown.status} />
            {document.data.legalHold && <LegalHoldBadge holds={document.data.legalHolds} />}
            <span>{t('docs.page.version', { label: shown.version })}</span>
            {document.data.classPath && document.data.classPath.length > 0 && (
              <span>· {document.data.classPath.join(' › ')}</span>
            )}
            <span>
              · {t('docs.page.author')}: <UserName id={document.data.authorId} />
            </span>
            {document.data.ownerId && (
              <span>
                · {t('docs.page.owner')}: <UserName id={document.data.ownerId} />
              </span>
            )}
          </div>
        )}
      </PageHeader>
      {document.isPending && <LoadingState label={t('docs.page.loading')} rows={6} />}
      {document.isError && (
        <ErrorState
          error={document.error}
          titles={{ notFound: t('docs.page.notFound'), forbidden: t('docs.page.forbidden') }}
          onRetry={() => void document.refetch()}
        />
      )}
      {document.data && shown && (
        <>
          <AccessNote document={document.data} />
          {showingOther && (
            <p className="rounded-md border bg-muted/50 p-3 text-sm" role="status">
              {t('docs.view.olderVersion', { label: shown.version })}{' '}
              <Link
                to="/documents/$documentId"
                params={{ documentId }}
                search={{}}
                className="text-primary underline-offset-4 hover:underline"
              >
                {t('docs.view.toCurrent')}
              </Link>
            </p>
          )}
          {showsChangeSummary(shown, editing) && (
            <ChangeSummaryPanel
              key={`summary-${shown.id}`}
              documentId={document.data.id}
              version={shown}
              editable={editing}
            />
          )}
          {editing ? (
            <>
              <DocumentEditor
                // A new open version (e.g. after a return) starts a fresh form; saves keep the form as is.
                key={shown.id}
                templateId={shown.templateId}
                version={shown.templateVersion}
                language={language ?? documentLanguage(document.data)}
                draft={{ documentId: document.data.id, version: shown }}
                kind={fileDocument ? 'file' : 'structured'}
                aside={
                  fileDocument ? (
                    <FileContentPanel documentId={document.data.id} version={shown} editable />
                  ) : undefined
                }
              />
              <VersionMetadata document={document.data} version={shown} />
            </>
          ) : (
            <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
              {fileDocument ? (
                <FileContentPanel documentId={document.data.id} version={shown} editable={false} />
              ) : (
                <VersionContent documentId={document.data.id} version={shown} />
              )}
              <VersionMetadata document={document.data} version={shown} />
            </div>
          )}
          <AttachmentsPanel
            key={`attachments-${shown.id}`}
            documentId={document.data.id}
            versionId={shown.id}
          />
          {!showingOther && asksForAcknowledgement(shown) && (
            <AcknowledgementPanel key={shown.id} documentId={document.data.id} version={shown} />
          )}
          {!showingOther && asksForAcknowledgement(shown) && (
            <AcknowledgementOverviewPanel documentId={document.data.id} />
          )}
          <WorkflowPanel
            documentId={document.data.id}
            title={document.data.title}
            versions={{ [document.data.current.id]: document.data.current.version }}
          />
          <VersionHistory documentId={document.data.id} shownId={shown.id} />
        </>
      )}
    </div>
  )
}
