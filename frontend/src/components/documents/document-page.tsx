import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentQuery } from '@/api/documents'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { templateFieldsQuery, type DocumentLanguage } from '@/api/templates'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { displayValue } from './draft-values'
import { LanguageSelect } from './document-workspace'
import { DocumentEditor } from './new-document-page'
import { StatusBadge } from './status-badge'
import { WorkflowPanel } from './workflow-panel'
import { AcknowledgementPanel } from './acknowledgement-panel'
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
        {fields.isPending && <Skeleton className="h-24 w-full" />}
        {fields.isError && (
          <p role="alert" className="text-sm text-destructive">
            {fields.error.message}
          </p>
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

function LoadError({ error }: { error: Error }) {
  const text =
    error instanceof ApiRequestError && error.status === 404
      ? 'This document does not exist.'
      : error instanceof ApiRequestError && error.status === 403
        ? 'You are not allowed to see this document.'
        : error.message
  return (
    <p role="alert" className="text-sm text-destructive">
      {text}
    </p>
  )
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
      <Link
        to="/documents"
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" />
        Documents
      </Link>
      {document.isPending && <Skeleton className="h-64 w-full" />}
      {document.isError && <LoadError error={document.error} />}
      {document.data && (
        <>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div className="space-y-1">
              <h1 className="font-heading text-2xl font-semibold">{document.data.title}</h1>
              <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                <StatusBadge status={document.data.current.status} />
                <span>Version {document.data.current.version}</span>
                {document.data.classPath && document.data.classPath.length > 0 && (
                  <span>· {document.data.classPath.join(' › ')}</span>
                )}
              </div>
            </div>
            {document.data.current.status === 'Draft' && (
              <div className="w-48">
                <LanguageSelect
                  value={language ?? documentLanguage(document.data)}
                  onChange={setLanguage}
                />
              </div>
            )}
          </div>
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
