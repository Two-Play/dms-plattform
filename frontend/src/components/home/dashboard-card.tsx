import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

/** The state of the query (or queries) behind a card. */
export interface CardQuery {
  isPending: boolean
  error: unknown
  refetch: () => void
}

/**
 * A card of the personal dashboard: title, count, up to a few items and a "view all" link, with a
 * loading skeleton, an empty state and an error with "Try again".
 */
export function DashboardCard({
  title,
  description,
  icon: Icon,
  count,
  countLabel,
  query,
  loadingLabel,
  isEmpty,
  empty,
  footer,
  children,
}: {
  title: string
  description?: string
  icon: LucideIcon
  count?: number
  /** Screen-reader text for the count, e.g. "3 open tasks". */
  countLabel?: string
  query: CardQuery
  loadingLabel: string
  isEmpty: boolean
  empty: ReactNode
  footer?: ReactNode
  children?: ReactNode
}) {
  const ready = !query.isPending && !query.error
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="flex items-center gap-2">
            <Icon aria-hidden="true" className="size-4 text-muted-foreground" />
            {title}
          </h2>
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
        {ready && count !== undefined && (
          <CardAction>
            <p className="font-heading text-2xl font-semibold tabular-nums">
              <span aria-hidden="true">{count}</span>
              <span className="sr-only">{countLabel ?? String(count)}</span>
            </p>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="flex-1">
        {query.isPending ? (
          <LoadingState label={loadingLabel} rows={3} />
        ) : query.error ? (
          <ErrorState error={query.error} onRetry={query.refetch} />
        ) : isEmpty ? (
          empty
        ) : (
          children
        )}
      </CardContent>
      {footer && ready && <CardFooter className="justify-end">{footer}</CardFooter>}
    </Card>
  )
}

/** A short list of items inside a dashboard card. */
export function CardList({ label, children }: { label: string; children: ReactNode }) {
  return (
    <ul aria-label={label} className="divide-y">
      {children}
    </ul>
  )
}

export function CardListItem({ title, meta }: { title: ReactNode; meta?: ReactNode }) {
  return (
    <li className="flex flex-col gap-1 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0 font-medium break-words">{title}</div>
      {meta && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          {meta}
        </div>
      )}
    </li>
  )
}
