import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from 'cn'
import { Skeleton } from '@/components/ui/skeleton'

/** Placeholder while content loads: skeleton rows and a screen-reader text. */
export function LoadingState({
  label,
  rows = 3,
  className,
}: {
  label: string
  rows?: number
  className?: string
}) {
  return (
    <div role="status" className={cn('space-y-2', className)}>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  )
}

/** Nothing to show yet, with what to do next. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: LucideIcon
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-2 rounded-lg border border-dashed px-4 py-6 text-center text-sm',
        className,
      )}
    >
      {Icon && <Icon aria-hidden="true" className="size-6 text-muted-foreground" />}
      <p className="font-medium">{title}</p>
      {description && <p className="max-w-prose text-muted-foreground">{description}</p>}
      {action && <div className="pt-1">{action}</div>}
    </div>
  )
}
