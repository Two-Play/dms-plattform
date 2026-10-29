import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { SystemStatusCard } from '@/components/system-status-card'
import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { deputyshipsQuery } from '@/api/deputyships'
import { hasRole, useMe } from '@/api/me'
import { DeputyshipForm } from '@/components/deputies/deputyship-form'
import { DeputyshipTable } from '@/components/deputies/deputyship-table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { t } from '@/i18n'
import { GroupsPanel } from './groups-panel'
import { NumberRangesPanel } from './number-ranges-panel'

/** All deputyships of the tenant for tenant administrators (DMS-99). */
function DeputyshipsPanel() {
  const client = useApiClient()
  const list = useQuery(deputyshipsQuery(client, 'all'))
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>{t('admin.setDeputy')}</CardTitle>
          <CardDescription>{t('admin.setDeputyText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <DeputyshipForm withPrincipal />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>{t('admin.allDeputyships')}</CardTitle>
          <CardDescription>{t('admin.allDeputyshipsText')}</CardDescription>
        </CardHeader>
        <CardContent>
          {list.isPending && <LoadingState label={t('admin.loadingDeputyships')} />}
          {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
          {list.data && (
            <DeputyshipTable
              deputyships={list.data}
              caption={t('admin.allCaption')}
              empty={t('admin.noDeputyships')}
            />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * Administration for tenant administrators: access groups for confidential documents (DMS-105),
 * number ranges (DMS-113) and the deputyships of everyone (DMS-99). The API enforces the
 * permissions.
 */
export function AdminPage() {
  const me = useMe()
  if (me.isPending) return <LoadingState label={t('admin.loading')} rows={4} />
  if (!hasRole(me.data, 'TenantAdmin')) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('admin.title')} />
        <p role="alert" className="text-sm text-muted-foreground">
          {t('admin.onlyAdmins')}
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-6">
      <PageHeader title={t('admin.title')} description={t('admin.description')} />
      <Tabs defaultValue="groups">
        <TabsList>
          <TabsTrigger value="groups">{t('admin.tabGroups')}</TabsTrigger>
          <TabsTrigger value="number-ranges">{t('admin.tabNumberRanges')}</TabsTrigger>
          <TabsTrigger value="deputyships">{t('admin.tabDeputyships')}</TabsTrigger>
          <TabsTrigger value="system">{t('admin.tabSystem')}</TabsTrigger>
        </TabsList>
        <TabsContent value="groups" className="pt-4">
          <GroupsPanel />
        </TabsContent>
        <TabsContent value="number-ranges" className="pt-4">
          <NumberRangesPanel />
        </TabsContent>
        <TabsContent value="deputyships" className="pt-4">
          <DeputyshipsPanel />
        </TabsContent>
        <TabsContent value="system" className="max-w-xl pt-4">
          <SystemStatusCard />
        </TabsContent>
      </Tabs>
    </div>
  )
}
