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
  formatPlainDate,
  isOverdue,
  kindLabels,
  reviewItem,
  sortInbox,
  workflowItem,
} from '@/components/tasks/inbox-items'
import { TemplateStatusBadge } from '@/components/templates/template-status-badge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CardList, CardListItem, DashboardCard } from './dashboard-card'

/** User-facing texts of the dashboard; one place so they can be translated later (DMS-110). */
const texts = {
  documentTitle: 'Home',
  welcome: 'Welcome',
  welcomeName: (name: string) => `Welcome, ${name}`,
  intro: 'What needs your attention today.',
  newDocument: 'New document',
  search: 'Search',
  untitled: 'Document',
  overdue: 'Overdue',
  version: (label: string) => `Version ${label}`,
  changedOn: (date: string) => `Changed ${date}`,
  publishedOn: (date: string) => `Published ${date}`,
  reviewDue: (date: string) => `Review due ${date}`,
  since: (date: string) => `Since ${date}`,
  tasks: {
    title: 'My open tasks',
    description: 'Reviews, approvals and periodic reviews.',
    count: (n: number) => (n === 1 ? '1 open task' : `${n} open tasks`),
    loading: 'Loading your tasks',
    list: 'Most urgent tasks',
    emptyTitle: 'You are all caught up',
    emptyText: 'New review and approval tasks will appear here and in the task inbox.',
    viewAll: 'View all tasks',
  },
  acknowledgements: {
    title: 'Read confirmations',
    description: 'Published versions you are asked to read.',
    count: (n: number) => (n === 1 ? '1 open read confirmation' : `${n} open read confirmations`),
    loading: 'Loading your read confirmations',
    list: 'Open read confirmations',
    emptyTitle: 'No read confirmations pending',
    emptyText: 'When a document you have to read is published, it appears here.',
    viewAll: 'View all read confirmations',
  },
  drafts: {
    title: 'My drafts',
    description: 'Drafts you started; continue or submit them.',
    count: (n: number) => (n === 1 ? '1 draft' : `${n} drafts`),
    loading: 'Loading your drafts',
    list: 'Recently changed drafts',
    emptyTitle: 'No drafts',
    emptyText: 'Start a new document from a template; it is saved as your draft.',
    viewAll: 'View all documents',
  },
  published: {
    title: 'Recently published',
    description: 'The newest valid documents.',
    loading: 'Loading recently published documents',
    list: 'Recently published documents',
    emptyTitle: 'Nothing published yet',
    emptyText: 'Published documents appear here once their approval is complete.',
    viewAll: 'Search all documents',
  },
  templates: {
    title: 'Templates in testing',
    description: 'Versions waiting for release by a second template designer.',
    count: (n: number) => (n === 1 ? '1 template in testing' : `${n} templates in testing`),
    loading: 'Loading templates',
    list: 'Templates in testing',
    emptyTitle: 'No templates awaiting release',
    emptyText: 'Template versions moved to testing appear here.',
    viewAll: 'View all templates',
  },
  compliance: {
    title: 'Compliance',
    description: 'Overdue periodic reviews and read confirmation rate.',
    count: (n: number) => (n === 1 ? '1 overdue review' : `${n} overdue reviews`),
    loading: 'Loading the compliance overview',
    overdue: 'Overdue reviews',
    rate: 'Read confirmation rate',
    open: 'Open the compliance cockpit',
  },
}

/** How many items a card lists before "view all". */
const TOP = 5

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}

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
      ...(tasks.data ?? []).map((t) => workflowItem(t, undefined)),
      ...(reviews.data ?? []).map(reviewItem),
    ],
    now,
  )
  return (
    <DashboardCard
      title={texts.tasks.title}
      description={texts.tasks.description}
      icon={CheckSquare}
      count={items.length}
      countLabel={texts.tasks.count(items.length)}
      loadingLabel={texts.tasks.loading}
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
          title={texts.tasks.emptyTitle}
          description={texts.tasks.emptyText}
        />
      }
      footer={<ViewAll to="/tasks" label={texts.tasks.viewAll} />}
    >
      <CardList label={texts.tasks.list}>
        {items.slice(0, TOP).map((item) => {
          const overdue = isOverdue(item, now)
          return (
            <CardListItem
              key={item.key}
              title={
                <DocumentLink
                  id={item.documentId}
                  title={item.title ?? titles.get(item.documentId) ?? texts.untitled}
                />
              }
              meta={
                <>
                  <Badge variant={item.kind === 'Approval' ? 'default' : 'secondary'}>
                    {kindLabels[item.kind]}
                  </Badge>
                  {overdue && <Badge variant="destructive">{texts.overdue}</Badge>}
                  <span>
                    {item.kind === 'ReviewDate'
                      ? texts.reviewDue(formatPlainDate(item.review.dueOn))
                      : texts.since(formatDate(item.createdAt))}
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
      title={texts.acknowledgements.title}
      description={texts.acknowledgements.description}
      icon={BookCheck}
      count={items.length}
      countLabel={texts.acknowledgements.count(items.length)}
      loadingLabel={texts.acknowledgements.loading}
      query={{
        isPending: acknowledgements.isPending,
        error: acknowledgements.error,
        refetch: () => void acknowledgements.refetch(),
      }}
      isEmpty={items.length === 0}
      empty={
        <EmptyState
          icon={CheckCircle2}
          title={texts.acknowledgements.emptyTitle}
          description={texts.acknowledgements.emptyText}
        />
      }
      footer={<ViewAll to="/tasks" label={texts.acknowledgements.viewAll} />}
    >
      <CardList label={texts.acknowledgements.list}>
        {items.slice(0, TOP).map((a) => (
          <CardListItem
            key={`${a.documentId}:${a.versionId}`}
            title={<DocumentLink id={a.documentId} title={a.title} />}
            meta={
              <>
                <span>{texts.version(a.versionLabel)}</span>
                {a.publishedAt && <span>{texts.publishedOn(formatDate(a.publishedAt))}</span>}
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
      title={texts.drafts.title}
      description={texts.drafts.description}
      icon={FilePen}
      count={drafts.length}
      countLabel={texts.drafts.count(drafts.length)}
      loadingLabel={texts.drafts.loading}
      query={{
        isPending: documents.isPending,
        error: documents.error,
        refetch: () => void documents.refetch(),
      }}
      isEmpty={drafts.length === 0}
      empty={
        <EmptyState
          icon={FilePen}
          title={texts.drafts.emptyTitle}
          description={texts.drafts.emptyText}
          action={
            <Button asChild size="sm">
              <Link to="/documents/new">
                <FilePlus />
                {texts.newDocument}
              </Link>
            </Button>
          }
        />
      }
      footer={<ViewAll to="/documents" label={texts.drafts.viewAll} />}
    >
      <CardList label={texts.drafts.list}>
        {drafts.slice(0, TOP).map((d) => (
          <CardListItem
            key={d.id}
            title={<DocumentLink id={d.id} title={d.title} />}
            meta={
              <>
                <span>{texts.version(d.version)}</span>
                <span>{texts.changedOn(formatDate(d.updatedAt))}</span>
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
      title={texts.published.title}
      description={texts.published.description}
      icon={FileText}
      loadingLabel={texts.published.loading}
      query={{
        isPending: documents.isPending,
        error: documents.error,
        refetch: () => void documents.refetch(),
      }}
      isEmpty={published.length === 0}
      empty={
        <EmptyState
          icon={FileText}
          title={texts.published.emptyTitle}
          description={texts.published.emptyText}
        />
      }
      footer={<ViewAll to="/search" label={texts.published.viewAll} />}
    >
      <CardList label={texts.published.list}>
        {published.slice(0, TOP).map((d) => (
          <CardListItem
            key={d.id}
            title={<DocumentLink id={d.id} title={d.title} />}
            meta={
              <>
                <StatusBadge status={d.status} />
                <span>{texts.version(d.version)}</span>
                <span>{texts.publishedOn(formatDate(d.updatedAt))}</span>
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
    .filter((t) => t.status === 'Testing')
    .sort((a, b) => a.name.localeCompare(b.name))
  return (
    <DashboardCard
      title={texts.templates.title}
      description={texts.templates.description}
      icon={LayoutTemplate}
      count={testing.length}
      countLabel={texts.templates.count(testing.length)}
      loadingLabel={texts.templates.loading}
      query={{ isPending: list.isPending, error: list.error, refetch: () => void list.refetch() }}
      isEmpty={testing.length === 0}
      empty={
        <EmptyState
          icon={LayoutTemplate}
          title={texts.templates.emptyTitle}
          description={texts.templates.emptyText}
        />
      }
      footer={<ViewAll to="/templates" label={texts.templates.viewAll} />}
    >
      <CardList label={texts.templates.list}>
        {testing.slice(0, TOP).map((t) => (
          <CardListItem
            key={t.templateId}
            title={
              <Link
                to="/templates/$templateId"
                params={{ templateId: t.templateId }}
                search={{ version: t.version }}
                className="underline-offset-4 hover:underline"
              >
                {t.name}
              </Link>
            }
            meta={
              <>
                <TemplateStatusBadge status={t.status} />
                <span>{texts.version(String(t.version))}</span>
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
      title={texts.compliance.title}
      description={texts.compliance.description}
      icon={ShieldCheck}
      count={overdue}
      countLabel={texts.compliance.count(overdue)}
      loadingLabel={texts.compliance.loading}
      query={{
        isPending: overview.isPending,
        error: overview.error,
        refetch: () => void overview.refetch(),
      }}
      isEmpty={false}
      empty={null}
      footer={
        <Button asChild variant="ghost" size="sm">
          <Link to="/compliance">{texts.compliance.open}</Link>
        </Button>
      }
    >
      {overview.data && (
        <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
          <dt className="text-muted-foreground">{texts.compliance.overdue}</dt>
          <dd className={overdue > 0 ? 'font-medium text-destructive' : undefined}>{overdue}</dd>
          <dt className="text-muted-foreground">{texts.compliance.rate}</dt>
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
        title={name ? texts.welcomeName(name) : texts.welcome}
        documentTitle={texts.documentTitle}
        description={texts.intro}
        actions={
          <>
            <Button asChild>
              <Link to="/documents/new">
                <FilePlus />
                {texts.newDocument}
              </Link>
            </Button>
            <Button asChild variant="outline">
              <Link to="/search">
                <Search />
                {texts.search}
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
