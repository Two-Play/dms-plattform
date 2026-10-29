import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  BookCheck,
  CheckCircle2,
  CheckSquare,
  FilePen,
  FilePlus,
  FileText,
  LayoutTemplate,
  Search,
  ShieldCheck,
} from 'lucide-react'
import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { openAcknowledgementsQuery } from '@/api/acknowledgements'
import { complianceOverviewQuery, formatRate } from '@/api/compliance'
import { useApiClient } from '@/api/context'
import { documentListQuery, type DocumentSummary } from '@/api/documents'
import { hasRole, useMe } from '@/api/me'
import { openReviewTasksQuery } from '@/api/reviews'
import { templateAdminListQuery } from '@/api/template-admin'
import { greetingName } from '@/auth/profile'
import { myTasksQuery } from '@/api/workflow'
import { StatusBadge } from '@/components/documents/status-badge'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState } from '@/components/shared/states'
import { SystemStatusCard } from '@/components/system-status-card'
import {
  isOverdue,
  kindLabel,
  reviewItem,
  sortInbox,
  workflowItem,
} from '@/components/tasks/inbox-items'
import { TemplateStatusBadge } from '@/components/templates/template-status-badge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CardList, CardListItem, DashboardCard } from './dashboard-card'
import { t } from '@/i18n'
import { formatDate, formatPlainDate } from '@/i18n/format'

/** How many items a card lists before "view all". */
const TOP = 5

function DocumentLink({ id, title }: { id: string; title: string }) {
  return (
    <Link
      to="/documents/$documentId"
      params={{ documentId: id }}
      className="underline-offset-4 hover:underline"
    >
      {title}
    </Link>
  )
}

function ViewAll({
  to,
  label,
}: {
  to: '/tasks' | '/documents' | '/search' | '/templates'
  label: string
}) {
  return (
    <Button asChild variant="ghost" size="sm">
      <Link to={to}>{label}</Link>
    </Button>
  )
}

function newestFirst(a: DocumentSummary, b: DocumentSummary): number {
  return Date.parse(b.updatedAt) - Date.parse(a.updatedAt)
}

/** The documents list, shared by the drafts and published cards and for task titles. */
function useDocumentList() {
  return useQuery(documentListQuery(useApiClient()))
}

function TasksCard() {
  const client = useApiClient()
  const tasks = useQuery(myTasksQuery(client))
  const reviews = useQuery(openReviewTasksQuery(client))
  const documents = useDocumentList()
  const [now] = useState(() => Date.now())
  const titles = new Map((documents.data ?? []).map((d) => [d.id, d.title]))
  const items = sortInbox(
    [
      ...(tasks.data ?? []).map((task) => workflowItem(task, undefined)),
      ...(reviews.data ?? []).map(reviewItem),
    ],
    now,
  )
  return (
    <DashboardCard
      title={t('home.tasks.title')}
      description={t('home.tasks.description')}
      icon={CheckSquare}
      count={items.length}
      countLabel={t('home.tasks.count', { count: items.length })}
      loadingLabel={t('home.tasks.loading')}
      query={{
        isPending: tasks.isPending || reviews.isPending,
        error: tasks.error ?? reviews.error,
        refetch: () => {
          void tasks.refetch()
          void reviews.refetch()
        },
      }}
      isEmpty={items.length === 0}
      empty={
        <EmptyState
          icon={CheckCircle2}
          title={t('home.tasks.emptyTitle')}
          description={t('home.tasks.emptyText')}
        />
      }
      footer={<ViewAll to="/tasks" label={t('home.tasks.viewAll')} />}
    >
      <CardList label={t('home.tasks.list')}>
        {items.slice(0, TOP).map((item) => {
          const overdue = isOverdue(item, now)
          return (
            <CardListItem
              key={item.key}
              title={
                <DocumentLink
                  id={item.documentId}
                  title={item.title ?? titles.get(item.documentId) ?? t('common.document')}
                />
              }
              meta={
                <>
                  <Badge variant={item.kind === 'Approval' ? 'default' : 'secondary'}>
                    {kindLabel(item.kind)}
                  </Badge>
                  {overdue && <Badge variant="destructive">{t('common.overdue')}</Badge>}
                  <span>
                    {item.kind === 'ReviewDate'
                      ? t('home.reviewDue', { date: formatPlainDate(item.review.dueOn) })
                      : t('home.since', { date: formatDate(item.createdAt) })}
                  </span>
                </>
              }
            />
          )
        })}
      </CardList>
    </DashboardCard>
  )
}

function AcknowledgementsCard() {
  const client = useApiClient()
  const acknowledgements = useQuery(openAcknowledgementsQuery(client))
  // `null`: the backend does not offer read confirmations; nothing to do then.
  const items = [...(acknowledgements.data ?? [])].sort(
    (a, b) => Date.parse(a.publishedAt ?? '') - Date.parse(b.publishedAt ?? ''),
  )
  return (
    <DashboardCard
      title={t('home.acknowledgements.title')}
      description={t('home.acknowledgements.description')}
      icon={BookCheck}
      count={items.length}
      countLabel={t('home.acknowledgements.count', { count: items.length })}
      loadingLabel={t('home.acknowledgements.loading')}
      query={{
        isPending: acknowledgements.isPending,
        error: acknowledgements.error,
        refetch: () => void acknowledgements.refetch(),
      }}
      isEmpty={items.length === 0}
      empty={
        <EmptyState
          icon={CheckCircle2}
          title={t('home.acknowledgements.emptyTitle')}
          description={t('home.acknowledgements.emptyText')}
        />
      }
      footer={<ViewAll to="/tasks" label={t('home.acknowledgements.viewAll')} />}
    >
      <CardList label={t('home.acknowledgements.list')}>
        {items.slice(0, TOP).map((a) => (
          <CardListItem
            key={`${a.documentId}:${a.versionId}`}
            title={<DocumentLink id={a.documentId} title={a.title} />}
            meta={
              <>
                <span>{t('common.version', { label: a.versionLabel })}</span>
                {a.publishedAt && (
                  <span>{t('home.publishedOn', { date: formatDate(a.publishedAt) })}</span>
                )}
              </>
            }
          />
        ))}
      </CardList>
    </DashboardCard>
  )
}

function DraftsCard() {
  const documents = useDocumentList()
  const drafts = (documents.data ?? [])
    .filter((d) => d.mine && d.status === 'Draft')
    .sort(newestFirst)
  return (
    <DashboardCard
      title={t('home.drafts.title')}
      description={t('home.drafts.description')}
      icon={FilePen}
      count={drafts.length}
      countLabel={t('home.drafts.count', { count: drafts.length })}
      loadingLabel={t('home.drafts.loading')}
      query={{
        isPending: documents.isPending,
        error: documents.error,
        refetch: () => void documents.refetch(),
      }}
      isEmpty={drafts.length === 0}
      empty={
        <EmptyState
          icon={FilePen}
          title={t('home.drafts.emptyTitle')}
          description={t('home.drafts.emptyText')}
          action={
            <Button asChild size="sm">
              <Link to="/documents/new">
                <FilePlus />
                {t('home.newDocument')}
              </Link>
            </Button>
          }
        />
      }
      footer={<ViewAll to="/documents" label={t('home.drafts.viewAll')} />}
    >
      <CardList label={t('home.drafts.list')}>
        {drafts.slice(0, TOP).map((d) => (
          <CardListItem
            key={d.id}
            title={<DocumentLink id={d.id} title={d.title} />}
            meta={
              <>
                <span>{t('common.version', { label: d.version })}</span>
                <span>{t('home.changedOn', { date: formatDate(d.updatedAt) })}</span>
              </>
            }
          />
        ))}
      </CardList>
    </DashboardCard>
  )
}

function PublishedCard() {
  const documents = useDocumentList()
  const published = (documents.data ?? []).filter((d) => d.status === 'Published').sort(newestFirst)
  return (
    <DashboardCard
      title={t('home.published.title')}
      description={t('home.published.description')}
      icon={FileText}
      loadingLabel={t('home.published.loading')}
      query={{
        isPending: documents.isPending,
        error: documents.error,
        refetch: () => void documents.refetch(),
      }}
      isEmpty={published.length === 0}
      empty={
        <EmptyState
          icon={FileText}
          title={t('home.published.emptyTitle')}
          description={t('home.published.emptyText')}
        />
      }
      footer={<ViewAll to="/search" label={t('home.published.viewAll')} />}
    >
      <CardList label={t('home.published.list')}>
        {published.slice(0, TOP).map((d) => (
          <CardListItem
            key={d.id}
            title={<DocumentLink id={d.id} title={d.title} />}
            meta={
              <>
                <StatusBadge status={d.status} />
                <span>{t('common.version', { label: d.version })}</span>
                <span>{t('home.publishedOn', { date: formatDate(d.updatedAt) })}</span>
              </>
            }
          />
        ))}
      </CardList>
    </DashboardCard>
  )
}

function TemplatesCard() {
  const client = useApiClient()
  const list = useQuery(templateAdminListQuery(client))
  const testing = (list.data ?? [])
    .filter((tpl) => tpl.status === 'Testing')
    .sort((a, b) => a.name.localeCompare(b.name))
  return (
    <DashboardCard
      title={t('home.templates.title')}
      description={t('home.templates.description')}
      icon={LayoutTemplate}
      count={testing.length}
      countLabel={t('home.templates.count', { count: testing.length })}
      loadingLabel={t('home.templates.loading')}
      query={{ isPending: list.isPending, error: list.error, refetch: () => void list.refetch() }}
      isEmpty={testing.length === 0}
      empty={
        <EmptyState
          icon={LayoutTemplate}
          title={t('home.templates.emptyTitle')}
          description={t('home.templates.emptyText')}
        />
      }
      footer={<ViewAll to="/templates" label={t('home.templates.viewAll')} />}
    >
      <CardList label={t('home.templates.list')}>
        {testing.slice(0, TOP).map((tpl) => (
          <CardListItem
            key={tpl.templateId}
            title={
              <Link
                to="/templates/$templateId"
                params={{ templateId: tpl.templateId }}
                search={{ version: tpl.version }}
                className="underline-offset-4 hover:underline"
              >
                {tpl.name}
              </Link>
            }
            meta={
              <>
                <TemplateStatusBadge status={tpl.status} />
                <span>{t('common.version', { label: String(tpl.version) })}</span>
              </>
            }
          />
        ))}
      </CardList>
    </DashboardCard>
  )
}

function ComplianceCard() {
  const client = useApiClient()
  const overview = useQuery(complianceOverviewQuery(client))
  const overdue = overview.data?.overdueReviews ?? 0
  return (
    <DashboardCard
      title={t('home.compliance.title')}
      description={t('home.compliance.description')}
      icon={ShieldCheck}
      count={overdue}
      countLabel={t('home.compliance.count', { count: overdue })}
      loadingLabel={t('home.compliance.loading')}
      query={{
        isPending: overview.isPending,
        error: overview.error,
        refetch: () => void overview.refetch(),
      }}
      isEmpty={false}
      empty={null}
      footer={
        <Button asChild variant="ghost" size="sm">
          <Link to="/compliance">{t('home.compliance.open')}</Link>
        </Button>
      }
    >
      {overview.data && (
        <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
          <dt className="text-muted-foreground">{t('home.compliance.overdue')}</dt>
          <dd className={overdue > 0 ? 'font-medium text-destructive' : undefined}>{overdue}</dd>
          <dt className="text-muted-foreground">{t('home.compliance.rate')}</dt>
          <dd>{formatRate(overview.data.acknowledgements.rate)}</dd>
        </dl>
      )}
    </DashboardCard>
  )
}

/**
 * Personal dashboard (DMS-109): open tasks and read confirmations, own drafts, recently published
 * documents, quick actions, and cards for template designers, compliance officers and tenant
 * administrators when the role applies.
 */
export function HomePage() {
  const auth = useAuth()
  const me = useMe()
  const name = greetingName(auth.user?.profile)
  return (
    <div className="space-y-6">
      <PageHeader
        title={name ? t('home.welcomeName', { name: name }) : t('home.welcome')}
        documentTitle={t('home.documentTitle')}
        description={t('home.intro')}
        actions={
          <>
            <Button asChild>
              <Link to="/documents/new">
                <FilePlus />
                {t('home.newDocument')}
              </Link>
            </Button>
            <Button asChild variant="outline">
              <Link to="/search">
                <Search />
                {t('home.search')}
              </Link>
            </Button>
          </>
        }
      />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <TasksCard />
        <AcknowledgementsCard />
        <DraftsCard />
        <PublishedCard />
        {hasRole(me.data, 'TemplateDesigner') && <TemplatesCard />}
        {hasRole(me.data, 'ComplianceOfficer') && <ComplianceCard />}
        {hasRole(me.data, 'TenantAdmin') && <SystemStatusCard />}
      </div>
    </div>
  )
}
