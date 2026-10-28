import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { healthQuery } from '@/api/health'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

/** Shows the backend's `GET /health` result. */
export function SystemStatusCard() {
  const client = useApiClient()
  const { data, error, isPending } = useQuery(healthQuery(client))

  return (
    <Card>
      <CardHeader>
        <CardTitle>System status</CardTitle>
        <CardDescription>Backend health check</CardDescription>
      </CardHeader>
      <CardContent>
        {isPending ? (
          <Skeleton className="h-10 w-48" aria-label="Loading system status" />
        ) : error ? (
          <div className="space-y-1">
            <Badge variant="destructive">Unavailable</Badge>
            <p className="text-sm text-muted-foreground">{error.message}</p>
          </div>
        ) : (
          <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
            <dt className="text-muted-foreground">Status</dt>
            <dd>
              <Badge variant={data.status === 'ok' ? 'default' : 'destructive'}>
                {data.status}
              </Badge>
            </dd>
            <dt className="text-muted-foreground">Version</dt>
            <dd className="font-mono">{data.version}</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
