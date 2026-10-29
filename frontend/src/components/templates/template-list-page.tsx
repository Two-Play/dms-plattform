import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { LayoutTemplate } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { templateAdminListQuery, type TemplateVersion } from '@/api/template-admin'
import { wordTemplatesAdminQuery } from '@/api/word'
import { Badge } from '@/components/ui/badge'
import { DataTable, type Column } from '@/components/shared/data-table'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { UserName } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { TemplateStatusBadge } from './template-status-badge'
import { NewWordTemplateCard } from './word-template-panel'

/** Built per render, so the headers follow the active language. `word`: ids of Word templates (DMS-115). */
const columns = (word: Set<string>): Column<TemplateVersion>[] => [
  {
    key: 'name',
    header: t('templates.list.name'),
    cell: (tpl) => (
      <>
        <Link
          to="/templates/$templateId"
          params={{ templateId: tpl.templateId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {tpl.name}
        </Link>
        {word.has(tpl.templateId) && (
          <Badge variant="secondary" className="ml-2">
            {t('word.templates.badge')}
          </Badge>
        )}
        {tpl.description && <p className="text-xs text-muted-foreground">{tpl.description}</p>}
      </>
    ),
  },
  {
    key: 'version',
    header: t('templates.list.latest'),
    cell: (tpl) => <span className="font-mono text-xs">{tpl.version}</span>,
  },
  {
    key: 'status',
    header: t('templates.list.status'),
    cell: (tpl) => <TemplateStatusBadge status={tpl.status} />,
  },
  {
    key: 'createdBy',
    header: t('templates.list.createdBy'),
    className: 'hidden sm:table-cell',
    cell: (tpl) => <UserName id={tpl.createdBy} />,
  },
  {
    key: 'created',
    header: t('templates.list.created'),
    className: 'hidden text-muted-foreground md:table-cell',
    cell: (tpl) => formatDateTime(tpl.createdAt),
  },
]

/** Templates for template designers (DMS-98): the latest version of every template with its status. */
export function TemplateListPage() {
  const client = useApiClient()
  const list = useQuery(templateAdminListQuery(client))
  const words = useQuery(wordTemplatesAdminQuery(client))
  const wordIds = new Set((words.data ?? []).map((w) => w.templateId))
  const templates = [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <div className="space-y-6">
      <PageHeader title={t('templates.list.title')} description={t('templates.list.description')} />
      {list.isPending && <LoadingState label={t('templates.list.loading')} rows={5} />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && (
        <Card>
          <CardHeader>
            <CardTitle>{t('templates.list.all')}</CardTitle>
            <CardDescription>{t('templates.list.allText')}</CardDescription>
          </CardHeader>
          <CardContent>
            {templates.length === 0 ? (
              <EmptyState
                icon={LayoutTemplate}
                title={t('templates.list.empty')}
                description={t('templates.list.emptyText')}
              />
            ) : (
              <DataTable
                rows={templates}
                columns={columns(wordIds)}
                caption={t('templates.list.caption')}
                rowKey={(tpl) => tpl.templateId}
              />
            )}
          </CardContent>
        </Card>
      )}
      {list.data && <NewWordTemplateCard templates={templates} />}
    </div>
  )
}
