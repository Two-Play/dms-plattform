import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { FilePen, FilePlus, FileText, Upload } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { documentListQuery, type DocumentSummary } from '@/api/documents'
import { hasRole, useMe } from '@/api/me'
import { DataTable, type Column } from '@/components/shared/data-table'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { LegalHoldBadge } from '@/components/legal-holds/legal-hold-badge'
import { StatusBadge } from './status-badge'

/** Built per render, so the headers follow the active language. */
const columns = (): Column<DocumentSummary>[] => [
  {
    key: 'title',
    header: t('docs.list.title_'),
    cell: (d) => (
      <Link
        to="/documents/$documentId"
        params={{ documentId: d.id }}
        className="font-medium underline-offset-4 hover:underline"
      >
        {d.title}
      </Link>
    ),
  },
  {
    key: 'number',
    header: t('docs.list.number'),
    cell: (d) => <span className="font-mono text-xs whitespace-nowrap">{d.documentNumber}</span>,
  },
  {
    key: 'status',
    header: t('docs.list.status'),
    cell: (d) => (
      <div className="flex flex-wrap gap-1">
        <StatusBadge status={d.status} />
        {d.legalHold && <LegalHoldBadge />}
      </div>
    ),
  },
  {
    key: 'version',
    header: t('docs.list.version'),
    cell: (d) => <span className="font-mono text-xs">{d.version}</span>,
  },
  {
    key: 'class',
    header: t('docs.list.class'),
    className: 'hidden text-muted-foreground sm:table-cell',
    cell: (d) => d.classPath?.join(' › '),
  },
  {
    key: 'changed',
    header: t('docs.list.changed'),
    className: 'hidden text-muted-foreground md:table-cell',
    cell: (d) => formatDateTime(d.updatedAt),
  },
]

function DocumentTable({ documents, caption }: { documents: DocumentSummary[]; caption: string }) {
  return <DataTable rows={documents} columns={columns()} caption={caption} rowKey={(d) => d.id} />
}

/** Documents: the caller's drafts to continue and the other documents they may read (DMS-90). */
export function DocumentListPage() {
  const client = useApiClient()
  const list = useQuery(documentListQuery(client))
  const drafts = list.data?.filter((d) => d.mine && d.status === 'Draft') ?? []
  const others = list.data?.filter((d) => !(d.mine && d.status === 'Draft')) ?? []
  // Uploading a finished file (DMS-127) is for those who may create documents or templates.
  const me = useMe()
  const mayUpload = (['Author', 'Owner', 'TemplateDesigner'] as const).some((r) =>
    hasRole(me.data, r),
  )

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('docs.list.title')}
        description={t('docs.list.description')}
        actions={
          <div className="flex flex-wrap gap-2">
            {mayUpload && (
              <Button asChild variant="outline">
                <Link to="/documents/upload">
                  <Upload />
                  {t('upload.entry')}
                </Link>
              </Button>
            )}
            <Button asChild>
              <Link to="/documents/new">
                <FilePlus />
                {t('docs.list.newDocument')}
              </Link>
            </Button>
          </div>
        }
      />
      {list.isPending && <LoadingState label={t('docs.list.loading')} rows={5} />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>{t('docs.list.myDrafts')}</CardTitle>
              <CardDescription>{t('docs.list.myDraftsText')}</CardDescription>
            </CardHeader>
            <CardContent>
              {drafts.length > 0 ? (
                <DocumentTable documents={drafts} caption={t('docs.list.myDrafts')} />
              ) : (
                <EmptyState
                  icon={FilePen}
                  title={t('docs.list.noDrafts')}
                  description={t('docs.list.noDraftsText')}
                />
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>{t('docs.list.others')}</CardTitle>
              <CardDescription>{t('docs.list.othersText')}</CardDescription>
            </CardHeader>
            <CardContent>
              {others.length > 0 ? (
                <DocumentTable documents={others} caption={t('docs.list.others')} />
              ) : (
                <EmptyState
                  icon={FileText}
                  title={t('docs.list.noOthers')}
                  description={t('docs.list.noOthersText')}
                />
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
