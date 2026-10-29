import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { deputyshipsQuery } from '@/api/deputyships'
import { useMe } from '@/api/me'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { DeputyshipForm } from './deputyship-form'
import { DeputyshipTable } from './deputyship-table'

/**
 * The caller's own deputyships (DMS-99): set a deputy for an absence, see who stands in for whom
 * and end a deputyship early. Everyone signed in may use it; the backend checks that the deputy
 * holds the same roles.
 */
export function MyDeputiesPage() {
  const client = useApiClient()
  const me = useMe()
  const list = useQuery(deputyshipsQuery(client, 'mine'))
  const userId = me.data?.userId
  const mine = (list.data ?? []).filter((d) => d.principal === userId)
  const forOthers = (list.data ?? []).filter((d) => d.deputy === userId)

  return (
    <div className="max-w-4xl space-y-6">
      <PageHeader
        title="Deputies"
        description="While a deputyship is active, the deputy can complete your open review and approval tasks on your behalf. You keep your tasks as well."
      />
      <Card>
        <CardHeader>
          <CardTitle>Set a deputy</CardTitle>
          <CardDescription>For a limited period, e.g. a vacation.</CardDescription>
        </CardHeader>
        <CardContent>
          <DeputyshipForm />
        </CardContent>
      </Card>
      {(list.isPending || me.isPending) && <LoadingState label="Loading your deputyships" />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && userId !== undefined && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>My deputies</CardTitle>
              <CardDescription>Who stands in for you.</CardDescription>
            </CardHeader>
            <CardContent>
              <DeputyshipTable
                deputyships={mine}
                caption="My deputies"
                empty="You have not set a deputy."
              />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>I stand in for</CardTitle>
              <CardDescription>
                Their tasks appear in your inbox "on behalf of" them while the period runs.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <DeputyshipTable
                deputyships={forOthers}
                caption="Deputyships in which I am the deputy"
                empty="Nobody has named you as deputy."
              />
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
