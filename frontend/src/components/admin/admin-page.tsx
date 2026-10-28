import { useQuery } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import { deputyshipsQuery } from '@/api/deputyships'
import { hasRole, useMe } from '@/api/me'
import { DeputyshipForm } from '@/components/deputies/deputyship-form'
import { DeputyshipTable } from '@/components/deputies/deputyship-table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
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
          {list.isPending && <Skeleton className="h-32 w-full" />}
          {list.isError && (
            <p role="alert" className="text-sm text-destructive">
              {list.error.message}
            </p>
          )}
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
  if (me.isPending) return <Skeleton className="h-40 w-full" />
  if (!hasRole(me.data, 'TenantAdmin')) {
    return (
      <div className="space-y-6">
        <h1 className="font-heading text-2xl font-semibold">Admin</h1>
        <p role="alert" className="text-sm text-muted-foreground">
          Only tenant administrators can use the administration.
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-6">
      <h1 className="font-heading text-2xl font-semibold">Admin</h1>
      <Tabs defaultValue="groups">
        <TabsList>
          <TabsTrigger value="groups">Access groups</TabsTrigger>
          <TabsTrigger value="deputyships">Deputyships</TabsTrigger>
        </TabsList>
        <TabsContent value="groups" className="pt-4">
          <GroupsPanel />
        </TabsContent>
        <TabsContent value="deputyships" className="pt-4">
          <DeputyshipsPanel />
        </TabsContent>
      </Tabs>
    </div>
  )
}
