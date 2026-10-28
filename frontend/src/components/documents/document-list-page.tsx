import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { FilePlus } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { documentListQuery, type DocumentSummary } from '@/api/documents'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from './status-badge'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function DocumentTable({ documents, caption }: { documents: DocumentSummary[]; caption: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            <th scope="col" className="py-2 pr-4 font-medium">
              Title
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Status
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Version
            </th>
            <th scope="col" className="hidden py-2 pr-4 font-medium sm:table-cell">
              Class
            </th>
            <th scope="col" className="hidden py-2 font-medium md:table-cell">
              Changed
            </th>
          </tr>
        </thead>
        <tbody>
          {documents.map((d) => (
            <tr key={d.id} className="border-b last:border-0">
              <td className="py-2 pr-4">
                <Link
                  to="/documents/$documentId"
                  params={{ documentId: d.id }}
                  className="font-medium underline-offset-4 hover:underline"
                >
                  {d.title}
                </Link>
              </td>
              <td className="py-2 pr-4">
                <StatusBadge status={d.status} />
              </td>
              <td className="py-2 pr-4 font-mono text-xs">{d.version}</td>
              <td className="hidden py-2 pr-4 text-muted-foreground sm:table-cell">
                {d.classPath?.join(' › ')}
              </td>
              <td className="hidden py-2 text-muted-foreground md:table-cell">
                {dateTime.format(new Date(d.updatedAt))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Documents: the caller's drafts to continue and the other documents they may read (DMS-90). */
export function DocumentListPage() {
  const client = useApiClient()
  const list = useQuery(documentListQuery(client))
  const drafts = list.data?.filter((d) => d.mine && d.status === 'Draft') ?? []
  const others = list.data?.filter((d) => !(d.mine && d.status === 'Draft')) ?? []

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="font-heading text-2xl font-semibold">Documents</h1>
        <Button asChild>
          <Link to="/documents/new">
            <FilePlus />
            New document
          </Link>
        </Button>
      </div>
      {list.isPending && <Skeleton className="h-40 w-full" />}
      {list.isError && (
        <p role="alert" className="text-sm text-destructive">
          {list.error.message}
        </p>
      )}
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
                <p className="text-sm text-muted-foreground">
                  No drafts. Start one with “New document”.
                </p>
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
                <p className="text-sm text-muted-foreground">No other documents yet.</p>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
