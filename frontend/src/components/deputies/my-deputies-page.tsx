import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { deputyshipsQuery } from '@/api/deputyships'
import { useMe } from '@/api/me'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
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
      <PageHeader title={t('deputies.title')} description={t('deputies.description')} />
      <Card>
        <CardHeader>
          <CardTitle>{t('deputies.setDeputy')}</CardTitle>
          <CardDescription>{t('deputies.setDeputyText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <DeputyshipForm />
        </CardContent>
      </Card>
      {(list.isPending || me.isPending) && <LoadingState label={t('deputies.loading')} />}
      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
      {list.data && userId !== undefined && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>{t('deputies.mine')}</CardTitle>
              <CardDescription>{t('deputies.mineText')}</CardDescription>
            </CardHeader>
            <CardContent>
              <DeputyshipTable
                deputyships={mine}
                caption={t('deputies.mine')}
                empty={t('deputies.noMine')}
              />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>{t('deputies.forOthers')}</CardTitle>
              <CardDescription>{t('deputies.forOthersText')}</CardDescription>
            </CardHeader>
            <CardContent>
              <DeputyshipTable
                deputyships={forOthers}
                caption={t('deputies.forOthersCaption')}
                empty={t('deputies.noForOthers')}
              />
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
