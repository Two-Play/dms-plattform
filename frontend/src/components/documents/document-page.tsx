import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentQuery } from '@/api/documents'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { useGroups } from '@/api/groups'
import { templateFieldsQuery, type DocumentLanguage, type MetadataValue } from '@/api/templates'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState } from '@/components/shared/error-state'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
  PageHeader,
} from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { allowedGroupsOf, confidentialityOf, isRestrictedLevel } from './access-groups'
import { displayValue } from './draft-values'
import { LanguageSelect } from './document-workspace'
import { DocumentEditor } from './new-document-page'
import { StatusBadge } from './status-badge'
import { WorkflowPanel } from './workflow-panel'
import { AcknowledgementPanel } from './acknowledgement-panel'
import { EvidenceReportAction } from './evidence-report-panel'
import { VersionHistory } from './version-history'

/** Whether the page shows the acknowledgement card for a version: valid and requiring it. */
function asksForAcknowledgement(version: DocumentVersionView): boolean {
  return (
    (version.status === 'Published' || version.status === 'ReviewDue') &&
    version.requiresAcknowledgement
  )
}

/** The language of the document if it has one the preview supports. */
function documentLanguage(document: DocumentView): DocumentLanguage {
  const value = document.current.values.language?.value
  return value?.type === 'Text' && value.value === 'de' ? 'de' : 'en'
}

/** The values of a version that is not a draft, labelled like the template's fields. */
function VersionValues({ document }: { document: DocumentView }) {
  const client = useApiClient()
  const fields = useQuery(
    templateFieldsQuery(client, document.current.templateId, document.current.templateVersion),
  )
  const labels = new Map((fields.data?.fields ?? []).map((f) => [f.key, f.label]))
  const shown = Object.entries(document.current.values).filter(([key]) => labels.has(key))
  return (
    <Card>
      <CardHeader>
        <CardTitle>Metadata</CardTitle>
      </CardHeader>
      <CardContent>
        {fields.isPending && <LoadingState label="Loading the metadata" />}
        {fields.isError && (
          <ErrorState error={fields.error} onRetry={() => void fields.refetch()} />
        )}
        {fields.data && (
          <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
            {shown.map(([key, field]) => (
              <div key={key} className="contents">
                <dt className="text-muted-foreground">{labels.get(key)}</dt>
                <dd>{displayValue(field.value)}</dd>
              </div>
            ))}
          </dl>
        )}
      </CardContent>
    </Card>
  )
}

const levelLabels: Record<string, string> = {
  Confidential: 'Confidential',
  StrictlyConfidential: 'Strictly confidential',
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
      <span className="font-medium">{levelLabels[level] ?? level}:</span>{' '}
      {listed.length > 0
        ? `visible to the author, the owner and members of ${listed.join(', ')}.`
        : 'visible to the author and the owner only; name the allowed groups before submitting.'}
    </p>
  )
}

const loadErrorTitles = {
  notFound: 'This document does not exist.',
  forbidden: 'You are not allowed to see this document.',
}

/**
 * A document (DMS-90): its draft to continue editing and submit, or the version the caller may read,
 * with its review and approval state.
 */
export function DocumentPage({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const document = useQuery(documentQuery(client, documentId))
  const [language, setLanguage] = useState<DocumentLanguage | undefined>(undefined)

  return (
    <div className="space-y-6">
      <PageHeader
        title={document.data?.title ?? 'Document'}
        breadcrumbs={
          <Breadcrumbs>
            <Link to="/documents" className={breadcrumbLinkClass}>
              Documents
            </Link>
            <BreadcrumbCurrent>{document.data?.title ?? 'Document'}</BreadcrumbCurrent>
          </Breadcrumbs>
        }
        actions={
          document.data && (
            <>
              {document.data.current.status === 'Draft' && (
                <div className="w-48">
                  <LanguageSelect
                    value={language ?? documentLanguage(document.data)}
                    onChange={setLanguage}
                  />
                </div>
              )}
              <EvidenceReportAction documentId={document.data.id} />
            </>
          )
        }
      >
        {document.data && (
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <StatusBadge status={document.data.current.status} />
            <span>Version {document.data.current.version}</span>
            {document.data.classPath && document.data.classPath.length > 0 && (
              <span>· {document.data.classPath.join(' › ')}</span>
            )}
          </div>
        )}
      </PageHeader>
      {document.isPending && <LoadingState label="Loading the document" rows={6} />}
      {document.isError && (
        <ErrorState
          error={document.error}
          titles={loadErrorTitles}
          onRetry={() => void document.refetch()}
        />
      )}
      {document.data && (
        <>
          <AccessNote document={document.data} />
          {document.data.current.status === 'Draft' ? (
            <DocumentEditor
              // A new open version (e.g. after a return) starts a fresh form; saves keep the form as is.
              key={document.data.current.id}
              templateId={document.data.current.templateId}
              version={document.data.current.templateVersion}
              language={language ?? documentLanguage(document.data)}
              draft={{ documentId: document.data.id, version: document.data.current }}
            />
          ) : (
            <VersionValues document={document.data} />
          )}
          {asksForAcknowledgement(document.data.current) && (
            <AcknowledgementPanel
              key={document.data.current.id}
              documentId={document.data.id}
              version={document.data.current}
            />
          )}
          <WorkflowPanel
            documentId={document.data.id}
            title={document.data.title}
            versions={{ [document.data.current.id]: document.data.current.version }}
          />
          <VersionHistory documentId={document.data.id} />
        </>
      )}
    </div>
  )
}
