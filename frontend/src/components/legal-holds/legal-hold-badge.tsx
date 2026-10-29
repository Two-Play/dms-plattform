import { Link } from '@tanstack/react-router'
import { Lock } from 'lucide-react'
import { cn } from 'cn'
import type { LegalHoldRef } from '@/api/legal-holds'
import { Badge, badgeVariants } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { t } from '@/i18n'
import { formatDate } from '@/i18n/format'

/**
 * "Legal Hold" marker of a document under legal hold (DMS-106). Everybody who may read the document
 * sees that it is held and cannot be destroyed. Only callers who may manage legal holds of it get
 * `holds` from the backend: for them the marker opens the names of the holds with links; nobody
 * else ever receives hold names.
 */
export function LegalHoldBadge({
  holds,
  className,
}: {
  holds?: LegalHoldRef[]
  className?: string
}) {
  if (!holds || holds.length === 0) {
    return (
      <Badge
        variant="destructive"
        className={className}
        title={t('legalHolds.badge.explanation')}
        data-testid="legal-hold-badge"
      >
        <Lock aria-hidden="true" />
        {t('legalHolds.badge.label')}
        <span className="sr-only">: {t('legalHolds.badge.explanation')}</span>
      </Badge>
    )
  }
  return (
    <Popover>
      <PopoverTrigger
        className={cn(badgeVariants({ variant: 'destructive' }), 'cursor-pointer', className)}
        aria-label={t('legalHolds.badge.withHolds', { count: holds.length })}
        data-testid="legal-hold-badge"
      >
        <Lock aria-hidden="true" />
        {t('legalHolds.badge.label')}
      </PopoverTrigger>
      <PopoverContent align="start" className="space-y-2 text-sm">
        <p className="font-medium">{t('legalHolds.badge.title')}</p>
        <p className="text-xs text-muted-foreground">{t('legalHolds.badge.explanation')}</p>
        <ul aria-label={t('legalHolds.badge.holds')} className="space-y-1.5">
          {holds.map((h) => (
            <li key={h.id}>
              <Link
                to="/legal-holds"
                search={{ hold: h.id }}
                className="font-medium underline-offset-4 hover:underline"
              >
                {h.name}
              </Link>
              <div className="text-xs text-muted-foreground">
                {h.matterReference
                  ? t('legalHolds.badge.matterSince', {
                      matter: h.matterReference,
                      date: formatDate(h.createdAt),
                    })
                  : t('legalHolds.badge.since', { date: formatDate(h.createdAt) })}
              </div>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}
