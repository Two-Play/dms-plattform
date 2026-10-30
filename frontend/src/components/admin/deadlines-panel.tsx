import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useApiClient } from '@/api/context'
import {
  deadlineSettingsQuery,
  escalationTargets,
  resetDeadlineSettings,
  saveDeadlineSettings,
  type DeadlinePolicy,
  type DeadlineSettings,
} from '@/api/workflow'
import { errorText } from '@/components/shared/error-messages'
import { ErrorState } from '@/components/shared/error-state'
import { roleLabel } from '@/components/shared/role-label'
import { LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

type Field = 'dueWorkingDays' | 'reminderWorkingDays' | 'escalationWorkingDays'

const fields: readonly Field[] = ['dueWorkingDays', 'reminderWorkingDays', 'escalationWorkingDays']

/** A whole number of days 0–60 as typed, or `undefined` if invalid. */
function parseDays(text: string): number | undefined {
  const trimmed = text.trim()
  if (!/^\d{1,2}$/.test(trimmed)) return undefined
  const days = Number(trimmed)
  return days <= 60 ? days : undefined
}

function targetLabel(target: string): string {
  return target === 'Owner' ? t('deadlines.owner') : roleLabel(target)
}

/**
 * The tenant's default deadlines of review and approval tasks (DMS-87): working days to the
 * deadline, the reminder before it, the escalation after it and who is told. Workflows and steps of a
 * class may set their own; changes apply to tasks created afterwards.
 */
export function DeadlinesPanel() {
  const settings = useQuery(deadlineSettingsQuery(useApiClient()))
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('deadlines.title')}</CardTitle>
        <CardDescription>{t('deadlines.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {settings.isPending ? (
          <LoadingState label={t('deadlines.loading')} />
        ) : settings.error ? (
          <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
        ) : (
          <DeadlinesForm key={settings.dataUpdatedAt} settings={settings.data} />
        )}
      </CardContent>
    </Card>
  )
}

function DeadlinesForm({ settings }: { settings: DeadlineSettings }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const initial = settings.deadlines
  const [text, setText] = useState<Record<Field, string>>({
    dueWorkingDays: String(initial.dueWorkingDays),
    reminderWorkingDays: String(initial.reminderWorkingDays),
    escalationWorkingDays: String(initial.escalationWorkingDays),
  })
  const [targets, setTargets] = useState<string[]>(initial.escalateTo ?? [])
  const parsed = Object.fromEntries(fields.map((f) => [f, parseDays(text[f])])) as Record<
    Field,
    number | undefined
  >
  const invalid = fields.filter((f) => parsed[f] === undefined)
  const reminderTooLate =
    parsed.dueWorkingDays !== undefined &&
    parsed.reminderWorkingDays !== undefined &&
    parsed.dueWorkingDays > 0 &&
    parsed.reminderWorkingDays >= parsed.dueWorkingDays
  const onSaved = (saved: DeadlineSettings, message: string) => {
    queryClient.setQueryData(['workflow', 'deadlines'], saved)
    toast(message)
  }
  const save = useMutation({
    mutationFn: (policy: DeadlinePolicy) => saveDeadlineSettings(client, policy),
    onSuccess: (saved) => {
      onSaved(saved, t('deadlines.saved'))
    },
    onError: (error) => {
      toast(t('deadlines.failed', { message: errorText(error) }), 'error')
    },
  })
  const reset = useMutation({
    mutationFn: () => resetDeadlineSettings(client),
    onSuccess: (saved) => {
      onSaved(saved, t('deadlines.resetDone'))
    },
    onError: (error) => {
      toast(t('deadlines.failed', { message: errorText(error) }), 'error')
    },
  })
  const installation = settings.installation
  return (
    <form
      className="space-y-6"
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        if (invalid.length > 0 || reminderTooLate) return
        save.mutate({
          dueWorkingDays: parsed.dueWorkingDays ?? 0,
          reminderWorkingDays: parsed.reminderWorkingDays ?? 0,
          escalationWorkingDays: parsed.escalationWorkingDays ?? 0,
          escalateTo: targets,
        })
      }}
    >
      <p className="text-sm text-muted-foreground" aria-live="polite">
        {settings.source === 'tenant'
          ? t('deadlines.sourceTenant')
          : t('deadlines.sourceInstallation', {
              due: installation.dueWorkingDays,
              reminder: installation.reminderWorkingDays,
              escalation: installation.escalationWorkingDays,
            })}
      </p>
      <div className="grid gap-4 sm:grid-cols-3">
        {fields.map((f) => (
          <div key={f} className="space-y-1">
            <Label htmlFor={`${id}-${f}`}>{t(`deadlines.fields.${f}`)}</Label>
            <Input
              id={`${id}-${f}`}
              inputMode="numeric"
              value={text[f]}
              aria-invalid={
                parsed[f] === undefined || (f === 'reminderWorkingDays' && reminderTooLate)
              }
              aria-describedby={`${id}-${f}-hint`}
              onChange={(e) => {
                setText((current) => ({ ...current, [f]: e.target.value }))
              }}
            />
            <p id={`${id}-${f}-hint`} className="text-xs text-muted-foreground">
              {parsed[f] === undefined ? t('deadlines.invalidDays') : t(`deadlines.hints.${f}`)}
            </p>
          </div>
        ))}
      </div>
      {reminderTooLate && (
        <p role="alert" className="text-sm text-destructive">
          {t('deadlines.reminderTooLate')}
        </p>
      )}
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">{t('deadlines.escalateTo')}</legend>
        <p className="text-xs text-muted-foreground">{t('deadlines.escalateToHint')}</p>
        {escalationTargets.map((target) => (
          <div key={target} className="flex items-center gap-2">
            <input
              id={`${id}-${target}`}
              type="checkbox"
              className="size-4"
              checked={targets.includes(target)}
              onChange={(e) => {
                setTargets((current) =>
                  e.target.checked
                    ? [...current.filter((x) => x !== target), target]
                    : current.filter((x) => x !== target),
                )
              }}
            />
            <Label htmlFor={`${id}-${target}`}>{targetLabel(target)}</Label>
          </div>
        ))}
      </fieldset>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? t('deadlines.saving') : t('deadlines.save')}
        </Button>
        {settings.source === 'tenant' && (
          <Button
            type="button"
            variant="outline"
            disabled={reset.isPending}
            onClick={() => {
              reset.mutate()
            }}
          >
            {t('deadlines.reset')}
          </Button>
        )}
      </div>
    </form>
  )
}
