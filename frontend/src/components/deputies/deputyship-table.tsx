import { InlineError } from '@/components/shared/error-state'
import { toast } from '@/lib/toast'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { endDeputyship, type Deputyship } from '@/api/deputyships'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cachedUserName, useUserLabel } from '@/api/users'
import { statusLabel } from '@/components/documents/status-label'
import { roleLabel } from '@/components/shared/role-label'
import { UserName } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'

const statusVariant = {
  Active: 'default',
  Scheduled: 'secondary',
  Ended: 'outline',
} as const

function StatusBadge({ status }: { status: string }) {
  const variant =
    status in statusVariant ? statusVariant[status as keyof typeof statusVariant] : 'outline'
  return <Badge variant={variant}>{statusLabel(status)}</Badge>
}

function EndButton({ deputyship }: { deputyship: Deputyship }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const deputyName = useUserLabel(deputyship.deputy)
  const principalName = useUserLabel(deputyship.principal)
  const end = useMutation({
    mutationFn: () => endDeputyship(client, deputyship.id),
    onSuccess: async () => {
      toast(
        t('deputies.ended', {
          deputy: cachedUserName(queryClient, deputyship.deputy),
          principal: cachedUserName(queryClient, deputyship.principal),
        }),
      )
      await queryClient.invalidateQueries({ queryKey: ['deputyships'] })
      // forwarded tasks leave the deputy's inbox
      await queryClient.invalidateQueries({ queryKey: ['workflow'] })
    },
  })
  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        size="sm"
        variant="outline"
        disabled={end.isPending}
        aria-label={t('deputies.endLabel', {
          deputy: deputyName ?? '',
          principal: principalName ?? '',
        })}
        onClick={() => {
          end.mutate()
        }}
      >
        {end.isPending ? t('deputies.ending') : t('deputies.endNow')}
      </Button>
      {end.isError && <InlineError error={end.error} className="text-xs" />}
    </div>
  )
}

/** The period, with the early end if the deputyship was ended before its period ran out. */
function Period({ d }: { d: Deputyship }) {
  return (
    <>
      <span className="whitespace-nowrap">{formatDateTime(d.validFrom)}</span> –{' '}
      <span className="whitespace-nowrap">{formatDateTime(d.validUntil)}</span>
      {d.endedAt && (
        <div className="text-xs text-muted-foreground">
          {t('deputies.endedAt', { date: formatDateTime(d.endedAt) })}
          {d.endedBy && (
            <>
              {' '}
              {t('deputies.by')} <UserName id={d.endedBy} />
            </>
          )}
        </div>
      )}
    </>
  )
}

/**
 * Deputyships as a table, people by display name (DMS-110). Running
 * and scheduled deputyships can be ended; the backend allows that to principal, deputy and tenant
 * administrators.
 */
export function DeputyshipTable({
  deputyships,
  caption,
  empty,
}: {
  deputyships: Deputyship[]
  caption: string
  empty: string
}) {
  if (deputyships.length === 0) {
    return <p className="text-sm text-muted-foreground">{empty}</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('deputies.principal')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('deputies.deputy')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('deputies.period')}
            </th>
            <th scope="col" className="hidden py-2 pr-4 font-medium md:table-cell">
              {t('deputies.covers')}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t('deputies.status')}
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">{t('deputies.actions')}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {deputyships.map((d) => (
            <tr key={d.id} className="border-b align-top last:border-0">
              <td className="py-2 pr-4 break-all">
                <UserName id={d.principal} />
              </td>
              <td className="py-2 pr-4 break-all">
                <UserName id={d.deputy} />
              </td>
              <td className="py-2 pr-4">
                <Period d={d} />
              </td>
              <td className="hidden py-2 pr-4 md:table-cell">
                {(d.roles ?? []).map(roleLabel).join(', ')}
                {d.documentClass && (
                  <div className="text-xs text-muted-foreground">
                    {t('deputies.classLabel', { key: d.documentClass })}
                  </div>
                )}
                {d.reason && <div className="text-xs text-muted-foreground">{d.reason}</div>}
              </td>
              <td className="py-2 pr-4">
                <StatusBadge status={d.status} />
              </td>
              <td className="py-2">{d.status !== 'Ended' && <EndButton deputyship={d} />}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
