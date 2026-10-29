import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowRight } from 'lucide-react'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  versionComparisonQuery,
  type BlockDiff,
  type ChangeSummary,
  type SectionDiff,
  type ValueChange,
  type VersionComparison,
} from '@/api/versions'
import { ErrorState } from '@/components/shared/error-state'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
  PageHeader,
} from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { displayValue } from './draft-values'
import { StatusBadge } from './status-badge'
import { changeKindLabel, describeChange } from './version-labels'

type Span = NonNullable<BlockDiff['spans']>[number]

/**
 * Words of a block with insertions and deletions marked. The text is rendered as React text nodes, never as
 * HTML, so content cannot inject markup.
 */
function Spans({ spans }: { spans: Span[] }) {
  return (
    <>
      {spans.map((span, i) => {
        const space = i > 0 ? ' ' : ''
        switch (span.kind) {
          case 'Inserted':
            return (
              <span key={i}>
                {space}
                <ins className="rounded-sm bg-emerald-100 px-0.5 text-emerald-950 no-underline dark:bg-emerald-900/50 dark:text-emerald-50">
                  <span className="sr-only">[inserted: </span>
                  {span.text}
                  <span className="sr-only">]</span>
                </ins>
              </span>
            )
          case 'Deleted':
            return (
              <span key={i}>
                {space}
                <del className="rounded-sm bg-red-100 px-0.5 text-red-950 line-through dark:bg-red-900/50 dark:text-red-50">
                  <span className="sr-only">[deleted: </span>
                  {span.text}
                  <span className="sr-only">]</span>
                </del>
              </span>
            )
          default:
            return (
              <span key={i}>
                {space}
                {span.text}
              </span>
            )
        }
      })}
    </>
  )
}

const blockBorder: Record<string, string> = {
  Inserted: 'border-emerald-500',
  Deleted: 'border-red-500',
  Changed: 'border-amber-500',
  Unchanged: 'border-transparent',
}

const blockPrefix: Record<string, string> = {
  ClauseRef: 'Clause',
  Conditional: 'If',
  Repeat: 'For each',
  Table: 'Table',
}

function Block({ block }: { block: BlockDiff }) {
  const changes = block.changes ?? []
  const depth = Math.max(0, (block.path ?? []).length - 1)
  const text =
    block.kind === 'PageBreak' ? (
      <span className="text-muted-foreground">Page break</span>
    ) : (
      <>
        {blockPrefix[block.kind] && (
          <span className="mr-1 text-muted-foreground">{blockPrefix[block.kind]}</span>
        )}
        <Spans spans={block.spans ?? []} />
      </>
    )
  return (
    <div
      data-status={block.status}
      className={`border-l-4 py-1 pl-3 ${blockBorder[block.status] ?? 'border-transparent'}`}
      style={{ marginLeft: `${String(depth)}rem` }}
    >
      {block.status !== 'Unchanged' && <span className="sr-only">{block.status} block: </span>}
      {block.kind === 'Heading' ? (
        <p className="font-semibold whitespace-pre-line">{text}</p>
      ) : (
        <p className="whitespace-pre-line">{text}</p>
      )}
      {changes.length > 0 && (
        <ul className="mt-1 flex flex-wrap gap-1">
          {changes.map((change, i) => (
            <li key={i}>
              <Badge variant="outline">{describeChange(change)}</Badge>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function sectionTitle(section: SectionDiff): string {
  return section.titleAfter ?? section.titleBefore ?? section.id
}

function Section({ section, showUnchanged }: { section: SectionDiff; showUnchanged: boolean }) {
  const all = section.blocks ?? []
  const blocks = showUnchanged ? all : all.filter((b) => b.status !== 'Unchanged')
  const renamed = section.status === 'Changed' && section.titleBefore !== section.titleAfter
  return (
    <section aria-label={sectionTitle(section)} className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-heading font-semibold">{sectionTitle(section)}</h3>
        {section.status === 'Inserted' && <Badge>New section</Badge>}
        {section.status === 'Deleted' && <Badge variant="destructive">Removed section</Badge>}
        {section.moved && <Badge variant="secondary">Moved</Badge>}
        {renamed && (
          <Badge variant="outline">Renamed from “{section.titleBefore ?? 'untitled'}”</Badge>
        )}
        {section.lockedBefore !== undefined &&
          section.lockedAfter !== undefined &&
          section.lockedBefore !== section.lockedAfter && (
            <Badge variant="outline">{section.lockedAfter ? 'Now locked' : 'Unlocked'}</Badge>
          )}
      </div>
      {blocks.map((block, i) => (
        // Blocks have no identity of their own; their order within the comparison is stable.
        <Block key={i} block={block} />
      ))}
    </section>
  )
}

function TextChanges({ comparison }: { comparison: VersionComparison }) {
  const [showUnchanged, setShowUnchanged] = useState(false)
  const { text } = comparison
  const all = text.sections ?? []
  const sections = showUnchanged ? all : all.filter((s) => s.status !== 'Unchanged')
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <p className="text-muted-foreground">
          {text.changed
            ? `${String(text.insertedWords)} words inserted, ${String(text.deletedWords)} deleted`
            : 'The text is identical.'}
        </p>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            className="size-4"
            checked={showUnchanged}
            onChange={(e) => {
              setShowUnchanged(e.target.checked)
            }}
          />
          Show unchanged text
        </label>
      </div>
      {sections.map((section) => (
        <Section key={section.id} section={section} showUnchanged={showUnchanged} />
      ))}
    </div>
  )
}

function origin(change: ValueChange, side: 'before' | 'after'): string | undefined {
  const kind = side === 'before' ? change.originBefore : change.originAfter
  const source = side === 'before' ? change.sourceBefore : change.sourceAfter
  if (kind === 'Computed') return 'computed'
  if (kind === 'System' && source && source !== 'dms') return `from ${source}`
  return undefined
}

function ValueCell({ change, side }: { change: ValueChange; side: 'before' | 'after' }) {
  const value = side === 'before' ? change.before : change.after
  const note = origin(change, side)
  return (
    <td className="py-2 pr-4 align-top">
      {value ? displayValue(value) : <span className="text-muted-foreground">—</span>}
      {note && <span className="ml-1 text-xs text-muted-foreground">({note})</span>}
    </td>
  )
}

function ValueChanges({
  changes,
  label,
  empty,
  comparison,
}: {
  changes: ValueChange[]
  label: string
  empty: string
  comparison: VersionComparison
}) {
  if (changes.length === 0) return <p className="text-sm text-muted-foreground">{empty}</p>
  return (
    <div className="overflow-x-auto">
      <table aria-label={label} className="w-full text-left text-sm">
        <thead className="text-muted-foreground">
          <tr>
            <th className="py-2 pr-4 font-medium">Field</th>
            <th className="py-2 pr-4 font-medium">{comparison.from.version}</th>
            <th className="py-2 pr-4 font-medium">{comparison.to.version}</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {changes.map((change) => (
            <tr key={change.key}>
              <th scope="row" className="py-2 pr-4 align-top font-normal text-muted-foreground">
                {change.label}
              </th>
              <ValueCell change={change} side="before" />
              <ValueCell change={change} side="after" />
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Summaries({ summaries }: { summaries: ChangeSummary[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Change summary</CardTitle>
      </CardHeader>
      <CardContent>
        {summaries.length === 0 ? (
          <p className="text-sm text-muted-foreground">No version lies between the two.</p>
        ) : (
          <ul aria-label="Change summaries" className="space-y-2 text-sm">
            {summaries.map((s) => (
              <li key={s.versionId}>
                <span className="font-medium">
                  {s.version} ({changeKindLabel(s.changeKind)})
                </span>
                {': '}
                {s.summary ?? (
                  <span className="text-muted-foreground">The author gave no summary.</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

const loadErrorTitles = {
  forbidden: 'You are not allowed to read one of these versions.',
  notFound: 'One of these versions does not exist.',
}

/**
 * Comparison of two versions of a document (US-05.1, DMS-54): the authors' change summaries on top, then the
 * text, field value and metadata changes separately.
 */
export function VersionComparePage({
  documentId,
  from,
  to,
}: {
  documentId: string
  from?: string
  to?: string
}) {
  const client = useApiClient()
  const comparison = useQuery({
    ...versionComparisonQuery(client, documentId, from ?? '', to ?? ''),
    enabled: Boolean(from && to),
  })
  const data = comparison.data
  const changedSections = (data?.text.sections ?? []).filter((s) => s.status !== 'Unchanged').length

  return (
    <div className="space-y-6">
      <PageHeader
        title="Compare versions"
        description={data?.title}
        breadcrumbs={
          <Breadcrumbs>
            <Link to="/documents" className={breadcrumbLinkClass}>
              Documents
            </Link>
            <Link
              to="/documents/$documentId"
              params={{ documentId }}
              className={breadcrumbLinkClass}
            >
              {data?.title ?? 'Document'}
            </Link>
            <BreadcrumbCurrent>Compare</BreadcrumbCurrent>
          </Breadcrumbs>
        }
      >
        {data && (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span>Version {data.from.version}</span>
            <StatusBadge status={data.from.status} />
            <ArrowRight className="size-4" aria-label="to" />
            <span>Version {data.to.version}</span>
            <StatusBadge status={data.to.status} />
          </div>
        )}
      </PageHeader>
      {(!from || !to) && (
        <p role="alert" className="text-sm text-destructive">
          Pick two versions in the version history of the document.
        </p>
      )}
      {from && to && comparison.isPending && (
        <LoadingState label="Loading the comparison" rows={6} />
      )}
      {comparison.isError && (
        <ErrorState
          error={comparison.error}
          titles={loadErrorTitles}
          onRetry={() => void comparison.refetch()}
        />
      )}
      {data && (
        <>
          <Summaries summaries={data.summaries ?? []} />
          <Tabs defaultValue="text">
            <TabsList>
              <TabsTrigger value="text">Text ({changedSections})</TabsTrigger>
              <TabsTrigger value="fields">Fields ({(data.fields ?? []).length})</TabsTrigger>
              <TabsTrigger value="metadata">Metadata ({(data.metadata ?? []).length})</TabsTrigger>
            </TabsList>
            <TabsContent value="text" className="pt-2">
              <TextChanges comparison={data} />
            </TabsContent>
            <TabsContent value="fields" className="pt-2">
              <ValueChanges
                changes={data.fields ?? []}
                label="Changed field values"
                empty="No field value changed."
                comparison={data}
              />
            </TabsContent>
            <TabsContent value="metadata" className="pt-2">
              <ValueChanges
                changes={data.metadata ?? []}
                label="Changed metadata"
                empty="No metadata changed."
                comparison={data}
              />
            </TabsContent>
          </Tabs>
        </>
      )}
    </div>
  )
}
