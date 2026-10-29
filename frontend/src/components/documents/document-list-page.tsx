import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { FilePen, FilePlus, FileText } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { documentListQuery, type DocumentSummary } from '@/api/documents'
import { DataTable, type Column } from '@/components/shared/data-table'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { StatusBadge } from './status-badge'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

const columns: Column<DocumentSummary>[] = [
  {
    key: 'title',
    header: 'Title',
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
  { key: 'status', header: 'Status', cell: (d) => <StatusBadge status={d.status} /> },
  {
    key: 'version',
    header: 'Version',
    cell: (d) => <span className="font-mono text-xs">{d.version}</span>,
  },
  {
    key: 'class',
    header: 'Class',
    className: 'hidden text-muted-foreground sm:table-cell',
    cell: (d) => d.classPath?.join(' › '),
  },
  {
    key: 'changed',
    header: 'Changed',
    className: 'hidden text-muted-foreground md:table-cell',
    cell: (d) => dateTime.format(new Date(d.updatedAt)),
  },
]

function DocumentTable({ documents, caption }: { documents: DocumentSummary[]; caption: string }) {
  return <DataTable rows={documents} columns={columns} caption={caption} rowKey={(d) => d.id} />
}

/** Documents: the caller's drafts to continue and the other documents they may read (DMS-90). */
export function DocumentListPage() {
  const client = useApiClient()
  const list = useQuery(documentListQuery(client))
  const drafts = list.data?.filter((d) => d.mine && d.status === 'Draft') ?? []
  const others = list.data?.filter((d) => !(d.mine && d.status === 'Draft')) ?? []

  return (
    <div className="space-y-6">
      <PageHeader
        title="Documents"
        description="Your drafts and the documents you may read."
        actions={
          <Button asChild>
            <Link to="/documents/new">
              <FilePlus />
              New document
            </Link>
          </Button>
        }
      />
      {list.isPending && <LoadingState label="Loading documents" rows={5} />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>My drafts</CardTitle>
              <CardDescription>
                Drafts you started; open one to continue or submit it.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {drafts.length > 0 ? (
                <DocumentTable documents={drafts} caption="My drafts" />
              ) : (
                <EmptyState
                  icon={FilePen}
                  title="No drafts"
                  description="Start one with “New document”; it is saved as your draft."
                />
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Other documents</CardTitle>
              <CardDescription>
                Documents in review, approved or published, and drafts shared with you.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {others.length > 0 ? (
                <DocumentTable documents={others} caption="Other documents" />
              ) : (
                <EmptyState
                  icon={FileText}
                  title="No other documents yet."
                  description="Documents in review, approved or published appear here."
                />
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
