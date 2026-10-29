import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { CalendarClock, Download, Search, X } from 'lucide-react'
import { useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  downloadValidAtCsv,
  SEARCH_PAGE_SIZE,
  searchQuery,
  validAtQuery,
  type FacetValue,
  type SearchHit,
  type ValidVersion,
} from '@/api/search'
import { StatusBadge } from '@/components/documents/status-badge'
import { statusLabel } from '@/components/documents/status-label'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { UserName } from '@/components/shared/user-name'
import { intlLocale, t } from '@/i18n'
import { formatDateTime, formatPlainDate as formatDate } from '@/i18n/format'
import { EmptyState, LoadingState } from '@/components/shared/states'
import type { SearchPageSearch } from './search-params'
import { snippetParts } from './snippet'

/** A document language code as a name in the active language, e.g. `de` → "German". */
function languageName(code: string): string {
  try {
    return new Intl.DisplayNames([intlLocale()], { type: 'language' }).of(code) ?? code
  } catch {
    return code
  }
}

type Filter = 'class' | 'status' | 'lang'

/** The snippet of a hit with its highlights; every part is rendered as text, never as HTML. */
export function Snippet({ snippet }: { snippet: string }) {
  return (
    <p className="text-sm text-muted-foreground">
      {snippetParts(snippet).map((part, i) =>
        part.highlighted ? (
          <mark
            key={i}
            className="rounded-sm bg-yellow-200 px-0.5 text-foreground dark:bg-yellow-500/40"
          >
            {part.text}
          </mark>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </p>
  )
}

function SearchForm({
  search,
  onSubmit,
}: {
  search: SearchPageSearch
  onSubmit: (q: string, validOn: string) => void
}) {
  const [q, setQ] = useState(search.q ?? '')
  const [validOn, setValidOn] = useState(search.validOn ?? '')
  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    onSubmit(q.trim(), validOn)
  }
  return (
    <form role="search" onSubmit={submit} className="flex flex-wrap items-end gap-3">
      <div className="grid min-w-60 flex-1 gap-1.5">
        <Label htmlFor="search-text">{t('search.text')}</Label>
        <Input
          id="search-text"
          type="search"
          value={q}
          placeholder={t('search.placeholder')}
          onChange={(e) => {
            setQ(e.target.value)
          }}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="search-valid-on">{t('search.validOn')}</Label>
        <Input
          id="search-valid-on"
          type="date"
          value={validOn}
          className="w-44"
          aria-describedby="search-valid-on-note"
          onChange={(e) => {
            setValidOn(e.target.value)
          }}
        />
      </div>
      <Button type="submit">
        <Search />
        {t('search.submit')}
      </Button>
      <p id="search-valid-on-note" className="w-full text-xs text-muted-foreground">
        {t('search.validOnNote')}
      </p>
    </form>
  )
}

function FacetGroup({
  title,
  values,
  selected,
  label = (v) => v,
  onToggle,
}: {
  title: string
  values: FacetValue[]
  selected: string | undefined
  label?: (value: string) => string
  onToggle: (value: string) => void
}) {
  if (values.length === 0 && !selected) return null
  const shown =
    selected && !values.some((v) => v.value === selected)
      ? [{ value: selected, count: 0 }, ...values]
      : values
  return (
    <section aria-label={title} className="space-y-1">
      <h3 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{title}</h3>
      <ul className="space-y-0.5">
        {shown.map((v) => (
          <li key={v.value}>
            <button
              type="button"
              aria-pressed={selected === v.value}
              onClick={() => {
                onToggle(v.value)
              }}
              className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-1 text-left text-sm hover:bg-muted aria-pressed:bg-secondary aria-pressed:font-medium"
            >
              <span>{label(v.value)}</span>
              <span className="text-xs text-muted-foreground tabular-nums">{v.count}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function HitItem({ hit }: { hit: SearchHit }) {
  return (
    <li className="space-y-1 border-b py-3 last:border-0">
      <div className="flex flex-wrap items-center gap-2">
        <Link
          to="/documents/$documentId"
          params={{ documentId: hit.documentId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {hit.title}
        </Link>
        <span className="font-mono text-xs">{hit.versionLabel}</span>
        <StatusBadge status={hit.status} />
      </div>
      <p className="text-xs text-muted-foreground">
        {hit.classPath.join(' › ')}
        {hit.validFrom && <> {t('search.validFrom', { date: formatDate(hit.validFrom) })}</>}
      </p>
      {hit.snippet && <Snippet snippet={hit.snippet} />}
    </li>
  )
}

function SearchResults({
  search,
  setFilter,
  setPage,
}: {
  search: SearchPageSearch
  setFilter: (filter: Filter, value: string | undefined) => void
  setPage: (page: number) => void
}) {
  const client = useApiClient()
  const q = search.q ?? ''
  const page = search.page ?? 1
  const result = useQuery({
    ...searchQuery(client, {
      q,
      class: search.class,
      status: search.status,
      lang: search.lang,
      page,
      size: SEARCH_PAGE_SIZE,
    }),
    enabled: q !== '',
  })

  if (q === '') {
    return (
      <EmptyState
        icon={Search}
        title={t('search.startTitle')}
        description={t('search.startText')}
      />
    )
  }
  if (result.isPending) return <LoadingState label={t('search.searching')} rows={4} />
  if (result.isError) {
    return <ErrorState error={result.error} onRetry={() => void result.refetch()} />
  }
  if (result.data === null) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t('search.unavailableTitle')}</CardTitle>
          <CardDescription>{t('search.unavailableText')}</CardDescription>
        </CardHeader>
      </Card>
    )
  }
  const { total, hits, facets, size } = result.data
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)))
  const toggle = (filter: Filter, current: string | undefined) => (value: string) => {
    setFilter(filter, current === value ? undefined : value)
  }
  return (
    <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
      <aside aria-label={t('search.filters')} className="space-y-4">
        <FacetGroup
          title={t('search.class')}
          values={facets.class}
          selected={search.class}
          onToggle={toggle('class', search.class)}
        />
        <FacetGroup
          title={t('search.status')}
          values={facets.status}
          selected={search.status}
          label={statusLabel}
          onToggle={toggle('status', search.status)}
        />
        <FacetGroup
          title={t('search.language')}
          values={facets.language}
          selected={search.lang}
          label={languageName}
          onToggle={toggle('lang', search.lang)}
        />
      </aside>
      <div className="space-y-3">
        <p className="text-sm text-muted-foreground" aria-live="polite">
          {t('search.results', { count: total })}
        </p>
        {hits.length > 0 ? (
          <ol aria-label={t('search.resultList')}>
            {hits.map((hit) => (
              <HitItem key={hit.versionId} hit={hit} />
            ))}
          </ol>
        ) : (
          <p className="text-sm text-muted-foreground">{t('search.noMatch')}</p>
        )}
        {pages > 1 && (
          <nav aria-label={t('search.pages')} className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page <= 1}
              onClick={() => {
                setPage(page - 1)
              }}
            >
              {t('search.previous')}
            </Button>
            <span className="text-sm text-muted-foreground">
              {t('search.pageOf', { page, pages })}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= pages}
              onClick={() => {
                setPage(page + 1)
              }}
            >
              {t('search.next')}
            </Button>
          </nav>
        )}
      </div>
    </div>
  )
}

/** Class keys of the listed versions with their number of versions (leaf class). */
function classFacet(items: ValidVersion[]): FacetValue[] {
  const counts = new Map<string, number>()
  for (const item of items) {
    const leaf = item.classPath?.at(-1)
    if (leaf) counts.set(leaf, (counts.get(leaf) ?? 0) + 1)
  }
  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => a.value.localeCompare(b.value))
}

function ValidAtResults({
  search,
  validOn,
  setFilter,
}: {
  search: SearchPageSearch
  validOn: string
  setFilter: (filter: Filter, value: string | undefined) => void
}) {
  const client = useApiClient()
  const params = { date: validOn, class: search.class }
  const result = useQuery(validAtQuery(client, params))
  const exportCsv = useMutation({ mutationFn: () => downloadValidAtCsv(client, params) })

  return (
    <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
      <aside aria-label={t('search.filters')} className="space-y-4">
        <FacetGroup
          title={t('search.class')}
          values={result.data ? classFacet(result.data.items ?? []) : []}
          selected={search.class}
          onToggle={(value) => {
            setFilter('class', search.class === value ? undefined : value)
          }}
        />
      </aside>
      <Card>
        <CardHeader className="flex flex-wrap items-start justify-between gap-2">
          <div className="space-y-1">
            <CardTitle>{t('search.validOnTitle', { date: formatDate(validOn) })}</CardTitle>
            <CardDescription>
              {result.data
                ? t('search.validOnTextZone', { zone: result.data.timeZone })
                : t('search.validOnText')}
            </CardDescription>
          </div>
          <Button
            variant="outline"
            disabled={exportCsv.isPending}
            onClick={() => {
              exportCsv.mutate()
            }}
          >
            <Download />
            {t('search.exportCsv')}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {search.q && <p className="text-xs text-muted-foreground">{t('search.textIgnored')}</p>}
          {exportCsv.isError && <InlineError error={exportCsv.error} />}
          {result.isPending && <LoadingState label={t('search.loadingValid')} rows={4} />}
          {result.isError && (
            <ErrorState error={result.error} onRetry={() => void result.refetch()} />
          )}
          {result.data &&
            ((result.data.items ?? []).length > 0 ? (
              <ValidAtTable items={result.data.items ?? []} date={validOn} />
            ) : (
              <p className="text-sm text-muted-foreground">{t('search.noneValid')}</p>
            ))}
        </CardContent>
      </Card>
    </div>
  )
}

function ValidAtTable({ items, date }: { items: ValidVersion[]; date: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">
          {t('search.tableCaption', { date: formatDate(date) })}
        </caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('search.colTitle')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('search.colVersion')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('search.colStatus')}
            </th>
            <th scope="col" className="hidden py-2 pr-4 font-medium lg:table-cell">
              {t('search.colScope')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('search.colValid')}
            </th>
            <th scope="col" className="hidden py-2 font-medium md:table-cell">
              {t('search.colApprovedBy')}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.versionId} className="border-b align-top last:border-0">
              <td className="py-2 pr-4">
                <Link
                  to="/documents/$documentId"
                  params={{ documentId: item.documentId }}
                  className="font-medium underline-offset-4 hover:underline"
                >
                  {item.title}
                </Link>
                <div className="text-xs text-muted-foreground">{item.classPath?.join(' › ')}</div>
              </td>
              <td className="py-2 pr-4 font-mono text-xs">{item.versionLabel}</td>
              <td className="py-2 pr-4">
                <StatusBadge status={item.status} />
              </td>
              <td className="hidden py-2 pr-4 text-muted-foreground lg:table-cell">
                {item.scope || t('search.allScopes')}
              </td>
              <td className="py-2 pr-4 text-xs whitespace-nowrap">
                {formatDateTime(item.validSince)} –{' '}
                {item.validUntil ? formatDateTime(item.validUntil) : t('search.today')}
              </td>
              <td className="hidden py-2 text-xs md:table-cell">
                {item.approvedBy ? <UserName id={item.approvedBy} /> : '—'}
                {item.actingFor && (
                  <div className="text-muted-foreground">
                    {t('search.onBehalfOf')} <UserName id={item.actingFor} />
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Search (DMS-52) with facets and highlighted snippets, and the "valid on" history (DMS-53). */
export function SearchPage() {
  const search = useSearch({ from: '/_app/search' })
  const navigate = useNavigate({ from: '/search' })

  const update = (changes: Partial<SearchPageSearch>) => {
    void navigate({ search: (prev) => ({ ...prev, ...changes }) })
  }
  const setFilter = (filter: Filter, value: string | undefined) => {
    update({ [filter]: value, page: undefined })
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t('search.title')} />
      <SearchForm
        key={`${search.q ?? ''}|${search.validOn ?? ''}`}
        search={search}
        onSubmit={(q, validOn) => {
          update({ q: q || undefined, validOn: validOn || undefined, page: undefined })
        }}
      />
      {search.validOn && (
        <div className="flex items-center gap-2 text-sm">
          <CalendarClock className="size-4 text-muted-foreground" />
          <span>{t('search.showing', { date: formatDate(search.validOn) })}</span>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              update({ validOn: undefined })
            }}
          >
            <X />
            {t('search.back')}
          </Button>
        </div>
      )}
      {search.validOn ? (
        <ValidAtResults search={search} validOn={search.validOn} setFilter={setFilter} />
      ) : (
        <SearchResults
          search={search}
          setFilter={setFilter}
          setPage={(page) => {
            update({ page: page > 1 ? page : undefined })
          }}
        />
      )}
    </div>
  )
}
