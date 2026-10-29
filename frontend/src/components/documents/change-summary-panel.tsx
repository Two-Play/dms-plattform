import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView } from '@/api/partners'
import { MAX_CHANGE_SUMMARY, updateChangeSummary } from '@/api/versions'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'
import { changeKindLabel } from './version-labels'

function failure(error: Error): string {
  if (error instanceof ApiRequestError && error.status === 403) return t('changeSummary.forbidden')
  return t('changeSummary.failed', { message: error.message })
}

function SummaryForm({
  documentId,
  version,
  onSaved,
  onCancel,
}: {
  documentId: string
  version: DocumentVersionView
  onSaved: (reason: string | undefined) => void
  onCancel: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [summary, setSummary] = useState(version.changeReason ?? '')
  const [attempted, setAttempted] = useState(false)
  const minor = version.changeKind === 'Minor'
  const trimmed = summary.trim()
  const problem =
    minor && trimmed === ''
      ? t('changeSummary.missing')
      : trimmed.length > MAX_CHANGE_SUMMARY
        ? t('newVersion.summaryTooLong', { max: MAX_CHANGE_SUMMARY, count: trimmed.length })
        : undefined

  const save = useMutation({
    mutationFn: () => updateChangeSummary(client, documentId, trimmed),
    onSuccess: async (updated) => {
      toast(t('changeSummary.saved'))
      onSaved(updated.changeReason)
      // The document page, the version history and comparisons show the summary.
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problem) return
    save.mutate()
  }

  const error = attempted ? problem : undefined
  const fieldId = `${id}-summary`
  const hintId = `${id}-hint`
  return (
    <form onSubmit={submit} className="grid gap-3" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={fieldId}>
          {minor ? t('changeSummary.labelRequired') : t('changeSummary.label')}
        </Label>
        <Textarea
          id={fieldId}
          value={summary}
          rows={4}
          autoFocus
          onChange={(e) => {
            setSummary(e.target.value)
          }}
          aria-required={minor}
          aria-invalid={error ? true : undefined}
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={error ? 'alert' : undefined}
          className={error ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {error ?? t('changeSummary.hint')}
        </p>
      </div>
      {save.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(save.error)}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="sm" disabled={save.isPending}>
          {save.isPending ? t('changeSummary.saving') : t('changeSummary.save')}
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
      </div>
    </form>
  )
}

/**
 * The author's change summary of a version (US-05.1, DMS-103): what changed and why, with the kind of
 * change. On a draft (`editable`) it can be changed until submission; readers see it in the version history
 * and above comparisons too.
 */
export function ChangeSummaryPanel({
  documentId,
  version,
  editable,
}: {
  documentId: string
  version: DocumentVersionView
  editable: boolean
}) {
  const [editing, setEditing] = useState(false)
  // The saved summary until the refetched version arrives.
  const [saved, setSaved] = useState<{ reason: string | undefined } | undefined>(undefined)
  const reason = saved ? saved.reason : version.changeReason

  return (
    <Card data-testid="change-summary">
      <CardHeader>
        <CardTitle>{t('changeSummary.title')}</CardTitle>
        <CardDescription>
          {t('changeSummary.kind', { kind: changeKindLabel(version.changeKind) })}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {editing ? (
          <SummaryForm
            documentId={documentId}
            version={{ ...version, changeReason: reason }}
            onSaved={(r) => {
              setSaved({ reason: r })
              setEditing(false)
            }}
            onCancel={() => {
              setEditing(false)
            }}
          />
        ) : (
          <>
            {reason ? (
              <p className="whitespace-pre-wrap">{reason}</p>
            ) : (
              <p className="text-muted-foreground">{t('changeSummary.empty')}</p>
            )}
            {editable && (
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditing(true)
                }}
              >
                <Pencil className="size-4" aria-hidden />
                {t('changeSummary.edit')}
              </Button>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
