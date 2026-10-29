import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { endDeputyship, type Deputyship } from '@/api/deputyships'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from './format'

const statusVariant = {
  Active: 'default',
  Scheduled: 'secondary',
  Ended: 'outline',
} as const

function StatusBadge({ status }: { status: string }) {
  const variant =
    status in statusVariant ? statusVariant[status as keyof typeof statusVariant] : 'outline'
  return <Badge variant={variant}>{status}</Badge>
}

function EndButton({ deputyship }: { deputyship: Deputyship }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const end = useMutation({
    mutationFn: () => endDeputyship(client, deputyship.id),
    onSuccess: async () => {
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
        aria-label={`End deputyship of ${deputyship.deputy} for ${deputyship.principal}`}
        onClick={() => {
          end.mutate()
        }}
      >
        {end.isPending ? 'Ending…' : 'End now'}
      </Button>
      {end.isError && (
        <p role="alert" className="text-xs text-destructive">
          {end.error.message}
        </p>
      )}
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
          ended {formatDateTime(d.endedAt)}
          {d.endedBy ? ` by ${d.endedBy}` : ''}
        </div>
      )}
    </>
  )
}

/**
 * Deputyships as a table. User ids are shown until a directory of display names exists. Running
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
              Principal
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Deputy
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Period
            </th>
            <th scope="col" className="hidden py-2 pr-4 font-medium md:table-cell">
              Covers
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Status
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {deputyships.map((d) => (
            <tr key={d.id} className="border-b align-top last:border-0">
              <td className="py-2 pr-4 break-all">{d.principal}</td>
              <td className="py-2 pr-4 break-all">{d.deputy}</td>
              <td className="py-2 pr-4">
                <Period d={d} />
              </td>
              <td className="hidden py-2 pr-4 md:table-cell">
                {(d.roles ?? []).join(', ')}
                {d.documentClass && (
                  <div className="text-xs text-muted-foreground">class {d.documentClass}</div>
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
