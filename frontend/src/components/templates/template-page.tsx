import { ErrorState } from '@/components/shared/error-state'
import {
  BreadcrumbCurrent,
  Breadcrumbs,
  breadcrumbLinkClass,
  PageHeader,
} from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { toast } from '@/lib/toast'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { useMe } from '@/api/me'
import {
  changeTemplateStatus,
  createVersion,
  requestOf,
  templateVersionsQuery,
  type TemplateStatus,
  type TemplateVersion,
} from '@/api/template-admin'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ContentEditor } from './content-editor'
import { FieldsPanel } from './fields-panel'
import { ReleaseDialog } from './release-dialog'
import {
  contentText,
  contributors,
  errorMessage,
  hasOpenVersion,
  statusActions,
  type StatusAction,
} from './template-lifecycle'
import { TemplateStatusBadge } from './template-status-badge'
import { TestModePanel } from './test-mode-panel'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function History({ version }: { version: TemplateVersion }) {
  const edited = (version.editedBy ?? []).filter((u) => u !== version.createdBy)
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-muted-foreground">Status</dt>
      <dd>
        <TemplateStatusBadge status={version.status} />
      </dd>
      <dt className="text-muted-foreground">Created</dt>
      <dd>
        {dateTime.format(new Date(version.createdAt))} by{' '}
        <span className="font-mono text-xs">{version.createdBy}</span>
      </dd>
      {edited.length > 0 && (
        <>
          <dt className="text-muted-foreground">Changed by</dt>
          <dd className="font-mono text-xs">{edited.join(', ')}</dd>
        </>
      )}
      {version.releasedAt && (
        <>
          <dt className="text-muted-foreground">Released</dt>
          <dd>
            {dateTime.format(new Date(version.releasedAt))} by{' '}
            <span className="font-mono text-xs">{version.releasedBy}</span>
          </dd>
        </>
      )}
      <dt className="text-muted-foreground">Document class</dt>
      <dd className="font-mono text-xs">
        {version.documentClassId} · version {version.documentClassVersion}
      </dd>
    </dl>
  )
}

interface StatusActionsProps {
  version: TemplateVersion
  versions: TemplateVersion[]
  userId: string | undefined
}

function StatusActions({ version, versions, userId }: StatusActionsProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<TemplateStatus | undefined>()

  const change = useMutation({
    mutationFn: (to: TemplateStatus) =>
      changeTemplateStatus(client, version.templateId, version.version, to),
    onSuccess: async (updated) => {
      setDialog(undefined)
      toast(`Version ${String(updated.version)} is now ${updated.status}.`)
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
    },
  })
  const newVersion = useMutation({
    mutationFn: () => createVersion(client, version.templateId, requestOf(version)),
    onSuccess: async (created) => {
      toast(`Version ${String(created.version)} was created as a draft.`)
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
    },
  })

  const isContributor = userId !== undefined && contributors(version).includes(userId)
  const latest = versions.at(-1)?.version === version.version
  const canStartNewVersion = latest && !hasOpenVersion(versions) && version.status !== 'Archived'

  function trigger(action: StatusAction) {
    change.reset()
    if (action.to === 'Released' || action.to === 'Deprecated') setDialog(action.to)
    else change.mutate(action.to)
  }

  const error = change.error ? errorMessage(change.error) : undefined

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {statusActions(version.status).map((action) => (
          <Button
            key={action.to}
            variant={action.to === 'Released' ? 'default' : 'outline'}
            disabled={change.isPending}
            onClick={() => {
              trigger(action)
            }}
          >
            {action.label}
          </Button>
        ))}
        {canStartNewVersion && (
          <Button
            variant="outline"
            disabled={newVersion.isPending}
            onClick={() => {
              newVersion.mutate()
            }}
          >
            New version
          </Button>
        )}
      </div>
      {version.status === 'Testing' && isContributor && (
        <p className="text-xs text-muted-foreground">
          You created or changed this version. Four-eyes principle: another template designer has to
          release it.
        </p>
      )}
      {error && dialog === undefined && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {newVersion.isError && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(newVersion.error)}
        </p>
      )}
      <ReleaseDialog
        version={version}
        open={dialog === 'Released'}
        onOpenChange={(open) => {
          if (!open) setDialog(undefined)
        }}
        onRelease={() => {
          change.mutate('Released')
        }}
        releasing={change.isPending}
        error={dialog === 'Released' ? error : undefined}
      />
      <Dialog
        open={dialog === 'Deprecated'}
        onOpenChange={(open) => {
          if (!open) setDialog(undefined)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Deprecate version {version.version}?</DialogTitle>
            <DialogDescription>
              The version leaves the catalog: no new documents can be created from it. Existing
              documents keep it.
            </DialogDescription>
          </DialogHeader>
          {error && dialog === 'Deprecated' && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setDialog(undefined)
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={change.isPending}
              onClick={() => {
                change.mutate('Deprecated')
              }}
            >
              Deprecate
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function VersionDetails({
  version,
  versions,
  userId,
}: {
  version: TemplateVersion
  versions: TemplateVersion[]
  userId: string | undefined
}) {
  const editable = version.status === 'Draft'
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle role="heading" aria-level={2}>
            Version {version.version}
          </CardTitle>
          <CardDescription>Status, history and status changes.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <History version={version} />
          <StatusActions version={version} versions={versions} userId={userId} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Fields</CardTitle>
          <CardDescription>Fields of the document class the template can use.</CardDescription>
        </CardHeader>
        <CardContent>
          <FieldsPanel key={contentText(version.content)} version={version} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Content</CardTitle>
          <CardDescription>
            {editable
              ? 'Edit the content of this draft as JSON; the server checks it against the document class.'
              : 'Only drafts can be changed. Create a new version to change a released template.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {editable ? (
            <ContentEditor version={version} />
          ) : (
            <pre className="max-h-96 overflow-auto rounded-lg bg-muted p-3 text-xs">
              {contentText(version.content)}
            </pre>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Test mode</CardTitle>
          <CardDescription>
            Generated sample data reaching every condition branch, with empty optional fields and
            long values; each case rendered like the preview.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <TestModePanel key={contentText(version.content)} version={version} />
        </CardContent>
      </Card>
    </div>
  )
}

/** A template for template designers (DMS-98): its versions, one version in detail. */
export function TemplatePage({ templateId, version }: { templateId: string; version?: number }) {
  const client = useApiClient()
  const versions = useQuery(templateVersionsQuery(client, templateId))
  const me = useMe()
  const all = versions.data ?? []
  const selected = all.find((v) => v.version === version) ?? all.at(-1)

  return (
    <div className="space-y-6">
      <PageHeader
        title={selected?.name ?? 'Template'}
        description={selected?.description}
        breadcrumbs={
          <Breadcrumbs>
            <Link to="/templates" className={breadcrumbLinkClass}>
              Templates
            </Link>
            <BreadcrumbCurrent>{selected?.name ?? 'Template'}</BreadcrumbCurrent>
          </Breadcrumbs>
        }
      />
      {versions.isPending && <LoadingState label="Loading the template" rows={5} />}
      {versions.isError && (
        <ErrorState error={versions.error} onRetry={() => void versions.refetch()} />
      )}
      {selected && (
        <>
          <nav aria-label="Versions" className="flex flex-wrap gap-2">
            {[...all].reverse().map((v) => (
              <Link
                key={v.version}
                to="/templates/$templateId"
                params={{ templateId }}
                search={{ version: v.version }}
                aria-current={v.version === selected.version ? 'page' : undefined}
                className="flex items-center gap-2 rounded-md border px-2 py-1 text-sm hover:bg-muted aria-[current=page]:border-primary aria-[current=page]:bg-muted"
              >
                Version {v.version}
                <TemplateStatusBadge status={v.status} />
              </Link>
            ))}
          </nav>
          <VersionDetails
            key={selected.version}
            version={selected}
            versions={all}
            userId={me.data?.userId}
          />
        </>
      )}
    </div>
  )
}
