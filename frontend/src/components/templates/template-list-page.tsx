import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useApiClient } from '@/api/context'
import { templateAdminListQuery } from '@/api/template-admin'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TemplateStatusBadge } from './template-status-badge'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Templates for template designers (DMS-98): the latest version of every template with its status. */
export function TemplateListPage() {
  const client = useApiClient()
  const list = useQuery(templateAdminListQuery(client))
  const templates = [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold">Templates</h1>
        <p className="text-sm text-muted-foreground">
          Edit drafts, run the test mode and move versions through testing, release and deprecation.
        </p>
      </div>
      {list.isPending && <Skeleton className="h-40 w-full" />}
      {list.isError && (
        <p role="alert" className="text-sm text-destructive">
          {list.error.message}
        </p>
      )}
      {list.data && (
        <Card>
          <CardHeader>
            <CardTitle>All templates</CardTitle>
            <CardDescription>Latest version of every template, in any status.</CardDescription>
          </CardHeader>
          <CardContent>
            {templates.length === 0 ? (
              <p className="text-sm text-muted-foreground">No templates yet.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <caption className="sr-only">Templates</caption>
                  <thead className="text-left text-xs text-muted-foreground">
                    <tr className="border-b">
                      <th scope="col" className="py-2 pr-4 font-medium">
                        Name
                      </th>
                      <th scope="col" className="py-2 pr-4 font-medium">
                        Latest version
                      </th>
                      <th scope="col" className="py-2 pr-4 font-medium">
                        Status
                      </th>
                      <th scope="col" className="hidden py-2 pr-4 font-medium sm:table-cell">
                        Created by
                      </th>
                      <th scope="col" className="hidden py-2 font-medium md:table-cell">
                        Created
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {templates.map((t) => (
                      <tr key={t.templateId} className="border-b last:border-0">
                        <td className="py-2 pr-4">
                          <Link
                            to="/templates/$templateId"
                            params={{ templateId: t.templateId }}
                            className="font-medium underline-offset-4 hover:underline"
                          >
                            {t.name}
                          </Link>
                          {t.description && (
                            <p className="text-xs text-muted-foreground">{t.description}</p>
                          )}
                        </td>
                        <td className="py-2 pr-4 font-mono text-xs">{t.version}</td>
                        <td className="py-2 pr-4">
                          <TemplateStatusBadge status={t.status} />
                        </td>
                        <td className="hidden py-2 pr-4 font-mono text-xs sm:table-cell">
                          {t.createdBy}
                        </td>
                        <td className="hidden py-2 text-muted-foreground md:table-cell">
                          {dateTime.format(new Date(t.createdAt))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
