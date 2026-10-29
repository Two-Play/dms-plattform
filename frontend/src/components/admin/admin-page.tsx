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
import { GroupsPanel } from './groups-panel'

/** All deputyships of the tenant for tenant administrators (DMS-99). */
function DeputyshipsPanel() {
  const client = useApiClient()
  const list = useQuery(deputyshipsQuery(client, 'all'))
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Set a deputy</CardTitle>
          <CardDescription>
            For yourself or, as tenant administrator, for someone else.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DeputyshipForm withPrincipal />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>All deputyships</CardTitle>
          <CardDescription>Latest period first, including ended ones.</CardDescription>
        </CardHeader>
        <CardContent>
          {list.isPending && <LoadingState label="Loading the deputyships" />}
          {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
          {list.data && (
            <DeputyshipTable
              deputyships={list.data}
              caption="All deputyships of the tenant"
              empty="No deputyships yet."
            />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * Administration for tenant administrators: access groups for confidential documents (DMS-105)
 * and the deputyships of everyone (DMS-99). The API enforces the permissions.
 */
export function AdminPage() {
  const me = useMe()
  if (me.isPending) return <LoadingState label="Loading" rows={4} />
  if (!hasRole(me.data, 'TenantAdmin')) {
    return (
      <div className="space-y-6">
        <PageHeader title="Admin" />
        <p role="alert" className="text-sm text-muted-foreground">
          Only tenant administrators can use the administration.
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-6">
      <PageHeader
        title="Admin"
        description="Access groups for confidential documents, deputyships and the system status."
      />
      <Tabs defaultValue="groups">
        <TabsList>
          <TabsTrigger value="groups">Access groups</TabsTrigger>
          <TabsTrigger value="deputyships">Deputyships</TabsTrigger>
          <TabsTrigger value="system">System status</TabsTrigger>
        </TabsList>
        <TabsContent value="groups" className="pt-4">
          <GroupsPanel />
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
