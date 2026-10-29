import { ChevronRight } from 'lucide-react'
import { Children, type ReactNode } from 'react'
import { useDocumentTitle } from '@/lib/document-title'
import { t } from '@/i18n'

/**
 * Heading of a page: breadcrumbs, title, description and actions. Sets the browser tab title
 * (`documentTitle`, default the title). The heading takes focus after navigation (app shell).
 */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumbs,
  documentTitle,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  breadcrumbs?: ReactNode
  /** Browser tab title; needed when `title` is not plain text. */
  documentTitle?: string
  /** Extra lines below the description, e.g. status badges. */
  children?: ReactNode
}) {
  useDocumentTitle(documentTitle ?? (typeof title === 'string' ? title : undefined))
  return (
    <div className="space-y-2">
      {breadcrumbs}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <h1
            tabIndex={-1}
            data-page-heading=""
            className="font-heading text-2xl font-semibold break-words focus:outline-none"
          >
            {title}
          </h1>
          {description && <p className="text-sm text-muted-foreground">{description}</p>}
          {children}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  )
}

/** Trail of parent pages; the last item is the current page. */
export function Breadcrumbs({ children }: { children: ReactNode }) {
  const items = Children.toArray(children)
  return (
    <nav aria-label={t('common.breadcrumb')}>
      <ol className="flex flex-wrap items-center gap-1 text-sm text-muted-foreground">
        {items.map((item, i) => (
          <li key={i} className="inline-flex min-h-6 items-center gap-1">
            {i > 0 && <ChevronRight aria-hidden="true" className="size-4" />}
            {item}
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** The current page at the end of the breadcrumbs. */
export function BreadcrumbCurrent({ children }: { children: ReactNode }) {
  return (
    <span aria-current="page" className="text-foreground">
      {children}
    </span>
  )
}

/** Class for links in the breadcrumbs (24px target height). */
export const breadcrumbLinkClass =
  'inline-flex min-h-6 items-center rounded-sm underline-offset-4 hover:text-foreground hover:underline'
