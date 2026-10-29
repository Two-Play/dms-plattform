import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { healthQuery } from '@/api/health'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'

/** Shows the backend's `GET /health` result (tenant administrators). */
export function SystemStatusCard() {
  const client = useApiClient()
  const { data, error, isPending, refetch } = useQuery(healthQuery(client))

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('system.title')}</h2>
        </CardTitle>
        <CardDescription>{t('system.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {isPending ? (
          <LoadingState label={t('system.loading')} rows={1} />
        ) : error ? (
          <ErrorState
            error={error}
            titles={{ server: t('system.unavailable'), unknown: t('system.unavailable') }}
            onRetry={() => void refetch()}
          />
        ) : (
          <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
            <dt className="text-muted-foreground">{t('system.status')}</dt>
            <dd>
              <Badge variant={data.status === 'ok' ? 'default' : 'destructive'}>
                {data.status}
              </Badge>
            </dd>
            <dt className="text-muted-foreground">{t('system.version')}</dt>
            <dd className="font-mono">{data.version}</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
