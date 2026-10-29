import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { BellRing, LayoutGrid, Library, List, Sparkles, X } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  LIBRARY_PAGE_SIZE,
  LIBRARY_SORTS,
  libraryQuery,
  type LibraryFacetValue,
  type LibraryItem,
  type LibraryResult,
  type LibrarySort,
} from '@/api/library'
import { DataTable, type Column } from '@/components/shared/data-table'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { formatDate, formatPlainDate } from '@/i18n/format'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import {
  hasFilters,
  libraryParams,
  splitValues,
  toggleValue,
  type LibraryFilterKey,
  type LibraryPageSearch,
} from './library-params'

const selectClass =
  'h-8 rounded-lg border border-input bg-transparent px-2 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30'

const LEVELS = ['Public', 'Internal', 'Confidential', 'StrictlyConfidential'] as const

/** The confidentiality level in the active language; unknown codes as they are. */
function levelLabel(code: string): string {
  return (LEVELS as readonly string[]).includes(code)
    ? t(`library.levels.${code as (typeof LEVELS)[number]}`)
    : code
}

/** "Valid from" of an item: its valid-from date, else the day it became valid. */
function validFromText(item: LibraryItem): string {
  return item.validFrom
    ? t('library.validFrom', { date: formatPlainDate(item.validFrom) })
    : t('library.noValidFrom', { date: formatDate(item.validSince) })
}

/** New, changed since, review due and open acknowledgement as badges. */
function Markers({ item, since }: { item: LibraryItem; since: string }) {
  return (
    <>
      {item.isNew && (
        <Badge>
          <Sparkles aria-hidden="true" />
          {t('library.new')}
        </Badge>
      )}
      {item.changed && (
        <Badge variant="secondary">{t('library.changed', { date: formatPlainDate(since) })}</Badge>
      )}
      {item.acknowledgementPending && (
        <Badge variant="destructive">
          <BellRing aria-hidden="true" />
          {t('library.pending')}
        </Badge>
      )}
      {item.status === 'ReviewDue' && <Badge variant="outline">{t('library.reviewDue')}</Badge>}
    </>
  )
}

function ItemCard({ item, since }: { item: LibraryItem; since: string }) {
  const titleId = useId()
  return (
    <li>
      <Card className="h-full">
        <CardHeader>
          <p className="text-xs text-muted-foreground">{item.classLabel}</p>
          <CardTitle>
            <h2 id={titleId} className="text-base leading-snug">
              <Link
                to="/documents/$documentId"
                params={{ documentId: item.documentId }}
                className="underline-offset-4 hover:underline"
              >
                {item.title}
              </Link>
            </h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-1 flex-col gap-3">
          <dl
            aria-labelledby={titleId}
            className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs text-muted-foreground"
          >
            {item.documentNumber && (
              <>
                <dt>{t('library.number')}</dt>
                <dd className="font-mono text-foreground">{item.documentNumber}</dd>
              </>
            )}
            <dt>{t('library.version')}</dt>
            <dd className="font-mono text-foreground">{item.version}</dd>
            {item.organizationalUnit && (
              <>
                <dt>{t('library.unit')}</dt>
                <dd className="text-foreground">{item.organizationalUnit}</dd>
              </>
            )}
          </dl>
          {item.summary && <p className="text-sm">{item.summary}</p>}
          <p className="text-xs text-muted-foreground">
            {validFromText(item)}
            {item.scope && <> · {t('library.scope', { scope: item.scope })}</>}
          </p>
          {item.tags.length > 0 && (
            <ul aria-label={t('library.tag')} className="flex flex-wrap gap-1">
              {item.tags.map((tag) => (
                <li key={tag}>
                  <Badge variant="outline">{tag}</Badge>
                </li>
              ))}
            </ul>
          )}
          <div className="mt-auto flex flex-wrap gap-1">
            <Markers item={item} since={since} />
          </div>
        </CardContent>
      </Card>
    </li>
  )
}

/** Built per render, so the headers follow the active language. */
const columns = (since: string): Column<LibraryItem>[] => [
  {
    key: 'title',
    header: t('library.colTitle'),
    cell: (item) => (
      <div className="space-y-0.5">
        <Link
          to="/documents/$documentId"
          params={{ documentId: item.documentId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {item.title}
        </Link>
        <div className="text-xs text-muted-foreground">{item.classLabel}</div>
        {item.summary && <div className="text-xs text-muted-foreground">{item.summary}</div>}
      </div>
    ),
  },
  {
    key: 'number',
    header: t('library.colNumber'),
    className: 'font-mono text-xs whitespace-nowrap',
    cell: (item) => item.documentNumber ?? '—',
  },
  {
    key: 'version',
    header: t('library.colVersion'),
    className: 'font-mono text-xs',
    cell: (item) => item.version,
  },
  {
    key: 'unit',
    header: t('library.colUnit'),
    className: 'hidden text-muted-foreground md:table-cell',
    cell: (item) => item.organizationalUnit ?? '—',
  },
  {
    key: 'validFrom',
    header: t('library.colValidFrom'),
    className: 'hidden whitespace-nowrap text-muted-foreground sm:table-cell',
    cell: (item) =>
      item.validFrom ? formatPlainDate(item.validFrom) : formatDate(item.validSince),
  },
  {
    key: 'notes',
    header: t('library.colNotes'),
    cell: (item) => (
      <div className="flex flex-wrap gap-1">
        <Markers item={item} since={since} />
      </div>
    ),
  },
]

/** Values of one filter with counts; several can be selected (alternatives). */
function FacetGroup({
  title,
  values,
  selected,
  label = (v) => v.label ?? v.value,
  onToggle,
}: {
  title: string
  values: LibraryFacetValue[]
  selected: string[]
  label?: (value: LibraryFacetValue) => string
  onToggle: (value: string) => void
}) {
  const missing = selected
    .filter((s) => !values.some((v) => v.value === s))
    .map((value) => ({ value, count: 0 }))
  const shown = [...missing, ...values]
  if (shown.length === 0) return null
  return (
    <section aria-label={title} className="space-y-1">
      <h2 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{title}</h2>
      <ul className="space-y-0.5">
        {shown.map((v) => (
          <li key={v.value}>
            <button
              type="button"
              aria-pressed={selected.includes(v.value)}
              onClick={() => {
                onToggle(v.value)
              }}
              className="flex min-h-8 w-full items-center justify-between gap-2 rounded-md px-2 py-1 text-left text-sm hover:bg-muted aria-pressed:bg-secondary aria-pressed:font-medium"
            >
              <span>{label(v)}</span>
              <span className="text-xs text-muted-foreground tabular-nums">{v.count}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** The free-text filter; updates the URL a moment after typing stops. */
function TextFilter({ value, onChange }: { value: string; onChange: (q: string) => void }) {
  const [text, setText] = useState(value)
  // A changed URL (e.g. the back button or "Clear filters") replaces what was typed.
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if (value !== text.trim()) setText(value)
  }
  const debounced = useDebouncedValue(text, 300)
  const handled = useRef(debounced)
  useEffect(() => {
    if (handled.current === debounced) return
    handled.current = debounced
    if (debounced.trim() !== value) onChange(debounced.trim())
  }, [debounced, value, onChange])
  return (
    <div className="grid min-w-56 flex-1 gap-1.5">
      <Label htmlFor="library-text">{t('library.text')}</Label>
      <Input
        id="library-text"
        type="search"
        value={text}
        placeholder={t('library.placeholder')}
        onChange={(e) => {
          setText(e.target.value)
        }}
      />
    </div>
  )
}

function Toolbar({
  search,
  since,
  update,
}: {
  search: LibraryPageSearch
  since: string | undefined
  update: (changes: Partial<LibraryPageSearch>) => void
}) {
  const view = search.view ?? 'cards'
  // Titles and numbers ascending, dates newest first (as the backend defaults).
  const defaultOrder = search.sort === 'validFrom' || search.sort === 'lastChanged' ? 'desc' : 'asc'
  return (
    <div role="search" className="flex flex-wrap items-end gap-3">
      <TextFilter
        value={search.q ?? ''}
        onChange={(q) => {
          update({ q: q || undefined, page: undefined })
        }}
      />
      <div className="grid gap-1.5">
        <Label htmlFor="library-sort">{t('library.sort')}</Label>
        <select
          id="library-sort"
          className={selectClass}
          value={search.sort ?? 'title'}
          onChange={(e) => {
            const sort = e.target.value as LibrarySort
            update({ sort: sort === 'title' ? undefined : sort, order: undefined, page: undefined })
          }}
        >
          {LIBRARY_SORTS.map((s) => (
            <option key={s} value={s}>
              {t(`library.sorts.${s}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="library-order">{t('library.order')}</Label>
        <select
          id="library-order"
          className={selectClass}
          value={search.order ?? defaultOrder}
          onChange={(e) => {
            const order = e.target.value === 'desc' ? 'desc' : 'asc'
            // The default order of the sort stays out of the URL.
            update({ order: order === defaultOrder ? undefined : order, page: undefined })
          }}
        >
          <option value="asc">{t('library.ascending')}</option>
          <option value="desc">{t('library.descending')}</option>
        </select>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="library-since">{t('library.since')}</Label>
        <Input
          id="library-since"
          type="date"
          className="w-44"
          value={search.since ?? since ?? ''}
          aria-describedby="library-since-note"
          onChange={(e) => {
            update({ since: e.target.value || undefined, page: undefined })
          }}
        />
      </div>
      <div role="group" aria-label={t('library.view')} className="flex gap-1">
        <Button
          variant={view === 'cards' ? 'secondary' : 'ghost'}
          size="icon"
          aria-label={t('library.cards')}
          aria-pressed={view === 'cards'}
          onClick={() => {
            update({ view: undefined })
          }}
        >
          <LayoutGrid />
        </Button>
        <Button
          variant={view === 'list' ? 'secondary' : 'ghost'}
          size="icon"
          aria-label={t('library.list')}
          aria-pressed={view === 'list'}
          onClick={() => {
            update({ view: 'list' })
          }}
        >
          <List />
        </Button>
      </div>
      <p id="library-since-note" className="w-full text-xs text-muted-foreground">
        {t('library.sinceNote')}
      </p>
    </div>
  )
}

function Pagination({
  page,
  pages,
  setPage,
}: {
  page: number
  pages: number
  setPage: (page: number) => void
}) {
  if (pages <= 1) return null
  return (
    <nav aria-label={t('library.pages')} className="flex items-center gap-2">
      <Button
        variant="outline"
        size="sm"
        disabled={page <= 1}
        onClick={() => {
          setPage(page - 1)
        }}
      >
        {t('library.previous')}
      </Button>
      <span className="text-sm text-muted-foreground">{t('library.pageOf', { page, pages })}</span>
      <Button
        variant="outline"
        size="sm"
        disabled={page >= pages}
        onClick={() => {
          setPage(page + 1)
        }}
      >
        {t('library.next')}
      </Button>
    </nav>
  )
}

function Results({
  result,
  search,
  update,
  clear,
}: {
  result: LibraryResult
  search: LibraryPageSearch
  update: (changes: Partial<LibraryPageSearch>) => void
  clear: () => void
}) {
  const { items, facets, total, size, since } = result
  const page = search.page ?? 1
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)))
  const toggle = (key: LibraryFilterKey) => (value: string) => {
    update({ [key]: toggleValue(search[key], value), page: undefined })
  }
  const filtered = hasFilters(search)
  return (
    <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
      <aside aria-label={t('library.filters')} className="space-y-4">
        <div className="space-y-1">
          <Button
            variant={search.changed ? 'secondary' : 'ghost'}
            size="sm"
            aria-pressed={Boolean(search.changed)}
            className="w-full justify-between"
            onClick={() => {
              update({ changed: search.changed ? undefined : true, page: undefined })
            }}
          >
            <span className="truncate">
              {t('library.changedOnly', { date: formatPlainDate(since) })}
            </span>
            <span className="text-xs text-muted-foreground tabular-nums">{facets.changed}</span>
          </Button>
          <Button
            variant={search.pending ? 'secondary' : 'ghost'}
            size="sm"
            aria-pressed={Boolean(search.pending)}
            className="w-full justify-between"
            onClick={() => {
              update({ pending: search.pending ? undefined : true, page: undefined })
            }}
          >
            <span className="truncate">{t('library.pendingOnly')}</span>
            <span className="text-xs text-muted-foreground tabular-nums">{facets.pending}</span>
          </Button>
        </div>
        <FacetGroup
          title={t('library.class')}
          values={facets.class}
          selected={splitValues(search.class)}
          onToggle={toggle('class')}
        />
        <FacetGroup
          title={t('library.unit')}
          values={facets.unit}
          selected={splitValues(search.unit)}
          onToggle={toggle('unit')}
        />
        <FacetGroup
          title={t('library.tag')}
          values={facets.tag}
          selected={splitValues(search.tag)}
          onToggle={toggle('tag')}
        />
        <FacetGroup
          title={t('library.confidentiality')}
          values={facets.confidentiality}
          selected={splitValues(search.confidentiality)}
          label={(v) => levelLabel(v.value)}
          onToggle={toggle('confidentiality')}
        />
      </aside>
      <div className="min-w-0 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {t('library.results', { count: total })}
          </p>
          {filtered && (
            <Button variant="ghost" size="sm" onClick={clear}>
              <X />
              {t('library.clear')}
            </Button>
          )}
        </div>
        {items.length === 0 ? (
          filtered ? (
            <EmptyState
              icon={Library}
              title={t('library.noMatchTitle')}
              description={t('library.noMatchText')}
              action={
                <Button variant="outline" size="sm" onClick={clear}>
                  {t('library.clear')}
                </Button>
              }
            />
          ) : (
            <EmptyState
              icon={Library}
              title={t('library.emptyTitle')}
              description={t('library.emptyText')}
            />
          )
        ) : search.view === 'list' ? (
          <DataTable
            rows={items}
            columns={columns(since)}
            caption={t('library.tableCaption')}
            rowKey={(item) => item.versionId}
          />
        ) : (
          <ul
            aria-label={t('library.resultList')}
            className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3"
          >
            {items.map((item) => (
              <ItemCard key={item.versionId} item={item} since={since} />
            ))}
          </ul>
        )}
        <Pagination
          page={page}
          pages={pages}
          setPage={(p) => {
            update({ page: p > 1 ? p : undefined })
          }}
        />
      </div>
    </div>
  )
}

/**
 * The library (DMS-119): the documents valid today that the user may read, with filters by class,
 * issuing body, tags and confidentiality, a free-text filter, sorting, "new"/"changed since"
 * markers and open acknowledgements. Everything is kept in the URL.
 */
export function LibraryPage() {
  const search = useSearch({ from: '/_app/library' })
  const navigate = useNavigate({ from: '/library' })
  const client = useApiClient()
  const result = useQuery(libraryQuery(client, libraryParams(search, LIBRARY_PAGE_SIZE)))

  const update = (changes: Partial<LibraryPageSearch>) => {
    void navigate({ search: (prev) => ({ ...prev, ...changes }) })
  }
  const clear = () => {
    void navigate({
      search: (prev) => ({
        since: prev.since,
        sort: prev.sort,
        order: prev.order,
        view: prev.view,
      }),
    })
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t('library.title')} description={t('library.description')} />
      <Toolbar search={search} since={result.data?.since} update={update} />
      {result.isPending && <LoadingState label={t('library.loading')} rows={6} />}
      {result.isError && <ErrorState error={result.error} onRetry={() => void result.refetch()} />}
      {result.data && (
        <div aria-busy={result.isPlaceholderData}>
          <Results result={result.data} search={search} update={update} clear={clear} />
        </div>
      )}
    </div>
  )
}
