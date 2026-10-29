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
import { UserName, UserNames } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { statusLabel } from '@/components/documents/status-label'
import { TemplateStatusBadge } from './template-status-badge'
import { TestModePanel } from './test-mode-panel'
import { newWordTemplateVersion, wordTemplateQuery, type WordTemplate } from '@/api/word'
import { WordTemplatePanel } from './word-template-panel'

function History({ version }: { version: TemplateVersion }) {
  const edited = (version.editedBy ?? []).filter((u) => u !== version.createdBy)
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-muted-foreground">{t('templates.page.status')}</dt>
      <dd>
        <TemplateStatusBadge status={version.status} />
      </dd>
      <dt className="text-muted-foreground">{t('templates.page.created')}</dt>
      <dd>
        {formatDateTime(version.createdAt)} {t('templates.page.by')}{' '}
        <UserName id={version.createdBy} />
      </dd>
      {edited.length > 0 && (
        <>
          <dt className="text-muted-foreground">{t('templates.page.changedBy')}</dt>
          <dd>
            <UserNames ids={edited} />
          </dd>
        </>
      )}
      {version.releasedAt && (
        <>
          <dt className="text-muted-foreground">{t('templates.page.released')}</dt>
          <dd>
            {formatDateTime(version.releasedAt)}
            {version.releasedBy && (
              <>
                {' '}
                {t('templates.page.by')} <UserName id={version.releasedBy} />
              </>
            )}
          </dd>
        </>
      )}
      <dt className="text-muted-foreground">{t('templates.page.documentClass')}</dt>
      <dd className="font-mono text-xs">
        {t('templates.page.classVersion', {
          id: version.documentClassId,
          version: version.documentClassVersion,
        })}
      </dd>
    </dl>
  )
}

interface StatusActionsProps {
  version: TemplateVersion
  versions: TemplateVersion[]
  userId: string | undefined
  /** A Word template: its next version takes the Word file over (DMS-115). */
  word?: boolean
}

function StatusActions({ version, versions, userId, word = false }: StatusActionsProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<TemplateStatus | undefined>()

  const change = useMutation({
    mutationFn: (to: TemplateStatus) =>
      changeTemplateStatus(client, version.templateId, version.version, to),
    onSuccess: async (updated) => {
      setDialog(undefined)
      toast(
        t('templates.page.statusChanged', {
          version: updated.version,
          status: statusLabel(updated.status),
        }),
      )
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
    },
  })
  const newVersion = useMutation({
    mutationFn: async () =>
      word
        ? (await newWordTemplateVersion(client, version.templateId)).version
        : (await createVersion(client, version.templateId, requestOf(version))).version,
    onSuccess: async (created) => {
      toast(t('templates.page.versionCreated', { version: created }))
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
      await queryClient.invalidateQueries({ queryKey: ['word-templates'] })
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
            {t('templates.page.newVersion')}
          </Button>
        )}
      </div>
      {version.status === 'Testing' && isContributor && (
        <p className="text-xs text-muted-foreground">{t('templates.page.fourEyes')}</p>
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
            <DialogTitle>
              {t('templates.page.deprecateTitle', { version: version.version })}
            </DialogTitle>
            <DialogDescription>{t('templates.page.deprecateText')}</DialogDescription>
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
              {t('common.cancel')}
            </Button>
            <Button
              variant="destructive"
              disabled={change.isPending}
              onClick={() => {
                change.mutate('Deprecated')
              }}
            >
              {t('templates.page.deprecate')}
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
  word,
}: {
  version: TemplateVersion
  versions: TemplateVersion[]
  userId: string | undefined
  /** The Word file, if the version is a Word template (DMS-115). */
  word?: WordTemplate | null
}) {
  const editable = version.status === 'Draft'
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle role="heading" aria-level={2}>
            {t('templates.page.version', { version: version.version })}
          </CardTitle>
          <CardDescription>{t('templates.page.detailsText')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <History version={version} />
          <StatusActions
            version={version}
            versions={versions}
            userId={userId}
            word={word !== undefined && word !== null}
          />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>{t('templates.page.fields')}</CardTitle>
          <CardDescription>{t('templates.page.fieldsText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <FieldsPanel key={contentText(version.content)} version={version} />
        </CardContent>
      </Card>
      {word ? (
        <WordTemplatePanel key={word.sha256} version={version} word={word} />
      ) : (
        <StructuredContent version={version} editable={editable} />
      )}
    </div>
  )
}

/** Content editor and test mode of a structured template version. */
function StructuredContent({ version, editable }: { version: TemplateVersion; editable: boolean }) {
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>{t('templates.page.content')}</CardTitle>
          <CardDescription>
            {editable ? t('templates.page.contentEditable') : t('templates.page.contentReadOnly')}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ContentEditor version={version} readOnly={!editable} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>{t('templates.page.testMode')}</CardTitle>
          <CardDescription>{t('templates.page.testModeText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <TestModePanel key={contentText(version.content)} version={version} />
        </CardContent>
      </Card>
    </>
  )
}

/** A template for template designers (DMS-98): its versions, one version in detail. */
export function TemplatePage({ templateId, version }: { templateId: string; version?: number }) {
  const client = useApiClient()
  const versions = useQuery(templateVersionsQuery(client, templateId))
  const me = useMe()
  const all = versions.data ?? []
  const selected = all.find((v) => v.version === version) ?? all.at(-1)
  const word = useQuery({
    ...wordTemplateQuery(client, templateId, selected?.version ?? 0),
    enabled: selected !== undefined,
  })

  return (
    <div className="space-y-6">
      <PageHeader
        title={selected?.name ?? t('templates.page.template')}
        description={selected?.description}
        breadcrumbs={
          <Breadcrumbs>
            <Link to="/templates" className={breadcrumbLinkClass}>
              {t('templates.page.breadcrumb')}
            </Link>
            <BreadcrumbCurrent>{selected?.name ?? t('templates.page.template')}</BreadcrumbCurrent>
          </Breadcrumbs>
        }
      />
      {versions.isPending && <LoadingState label={t('templates.page.loading')} rows={5} />}
      {versions.isError && (
        <ErrorState error={versions.error} onRetry={() => void versions.refetch()} />
      )}
      {selected && (
        <>
          <nav aria-label={t('templates.page.versions')} className="flex flex-wrap gap-2">
            {[...all].reverse().map((v) => (
              <Link
                key={v.version}
                to="/templates/$templateId"
                params={{ templateId }}
                search={{ version: v.version }}
                aria-current={v.version === selected.version ? 'page' : undefined}
                className="flex items-center gap-2 rounded-md border px-2 py-1 text-sm hover:bg-muted aria-[current=page]:border-primary aria-[current=page]:bg-muted"
              >
                {t('templates.page.version', { version: v.version })}
                <TemplateStatusBadge status={v.status} />
              </Link>
            ))}
          </nav>
          <VersionDetails
            key={selected.version}
            version={selected}
            versions={all}
            userId={me.data?.userId}
            word={word.data}
          />
        </>
      )}
    </div>
  )
}
