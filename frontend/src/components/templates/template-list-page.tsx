import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { LayoutTemplate } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { templateAdminListQuery, type TemplateVersion } from '@/api/template-admin'
import { DataTable, type Column } from '@/components/shared/data-table'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { TemplateStatusBadge } from './template-status-badge'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

const columns: Column<TemplateVersion>[] = [
  {
    key: 'name',
    header: 'Name',
    cell: (t) => (
      <>
        <Link
          to="/templates/$templateId"
          params={{ templateId: t.templateId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {t.name}
        </Link>
        {t.description && <p className="text-xs text-muted-foreground">{t.description}</p>}
      </>
    ),
  },
  {
    key: 'version',
    header: 'Latest version',
    cell: (t) => <span className="font-mono text-xs">{t.version}</span>,
  },
  { key: 'status', header: 'Status', cell: (t) => <TemplateStatusBadge status={t.status} /> },
  {
    key: 'createdBy',
    header: 'Created by',
    className: 'hidden font-mono text-xs sm:table-cell',
    cell: (t) => t.createdBy,
  },
  {
    key: 'created',
    header: 'Created',
    className: 'hidden text-muted-foreground md:table-cell',
    cell: (t) => dateTime.format(new Date(t.createdAt)),
  },
]

/** Templates for template designers (DMS-98): the latest version of every template with its status. */
export function TemplateListPage() {
  const client = useApiClient()
  const list = useQuery(templateAdminListQuery(client))
  const templates = [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <div className="space-y-6">
      <PageHeader
        title="Templates"
        description="Edit drafts, run the test mode and move versions through testing, release and deprecation."
      />
      {list.isPending && <LoadingState label="Loading templates" rows={5} />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && (
        <Card>
          <CardHeader>
            <CardTitle>All templates</CardTitle>
            <CardDescription>Latest version of every template, in any status.</CardDescription>
          </CardHeader>
          <CardContent>
            {templates.length === 0 ? (
              <EmptyState
                icon={LayoutTemplate}
                title="No templates yet."
                description="Templates are created through the template API; they appear here with their status."
              />
            ) : (
              <DataTable
                rows={templates}
                columns={columns}
                caption="Templates"
                rowKey={(t) => t.templateId}
              />
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
