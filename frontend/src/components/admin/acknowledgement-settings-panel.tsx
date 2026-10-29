import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import {
  acknowledgementSettingsQuery,
  removeAcknowledgementSettings,
  saveAcknowledgementSettings,
  type AcknowledgementSettings,
} from '@/api/acknowledgement-overview'
import { useApiClient } from '@/api/context'
import { useGroups } from '@/api/groups'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { roleLabel } from '@/components/shared/role-label'
import { LoadingState } from '@/components/shared/states'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/** What is being edited: a class (`classId`) or the tenant default. */
interface Target {
  classId: string | undefined
  name: string
  settings: AcknowledgementSettings | undefined
}

function recipientsText(s: AcknowledgementSettings): string {
  const parts = [...(s.roles ?? []).map(roleLabel), ...(s.groups ?? []), ...(s.units ?? [])]
  return parts.length === 0 ? t('ackSettings.everyReader') : parts.join(', ')
}

function remindersText(s: AcknowledgementSettings, defaultDays: number): string {
  if (s.reminderDays === undefined) return t('ackSettings.defaultDays', { days: defaultDays })
  return s.reminderDays === 0
    ? t('ackSettings.off')
    : t('ackSettings.days', { count: s.reminderDays })
}

/** The reminder interval as typed: undefined (inherit), a whole number 0–365, or `false` if invalid. */
function parseDays(text: string): number | undefined | false {
  const trimmed = text.trim()
  if (trimmed === '') return undefined
  if (!/^\d{1,3}$/.test(trimmed)) return false
  const days = Number(trimmed)
  return days <= 365 ? days : false
}

function SettingsDialog({
  target,
  roles,
  defaultDays,
  onClose,
}: {
  target: Target
  roles: string[]
  defaultDays: number
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const groups = useGroups()
  const id = useId()
  const [chosenRoles, setRoles] = useState<string[]>(target.settings?.roles ?? [])
  const [chosenGroups, setGroups] = useState<string[]>(target.settings?.groups ?? [])
  const [units, setUnits] = useState((target.settings?.units ?? []).join(', '))
  const [days, setDays] = useState(
    target.settings?.reminderDays === undefined ? '' : String(target.settings.reminderDays),
  )
  const [attempted, setAttempted] = useState(false)
  const parsedDays = parseDays(days)
  const daysProblem = parsedDays === false

  const save = useMutation({
    mutationFn: () =>
      saveAcknowledgementSettings(client, target.classId, {
        roles: chosenRoles,
        groups: chosenGroups,
        units: units
          .split(',')
          .map((u) => u.trim())
          .filter(Boolean),
        reminderDays: parsedDays === false ? undefined : parsedDays,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['acknowledgement-settings'] })
      toast(t('ackSettings.saved', { name: target.name }))
      onClose()
    },
  })

  function toggle(list: string[], value: string, set: (next: string[]) => void) {
    set(list.includes(value) ? list.filter((v) => v !== value) : [...list, value])
  }

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (daysProblem) return
    save.mutate()
  }

  const daysError = attempted && daysProblem
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('ackSettings.dialogTitle', { name: target.name })}</DialogTitle>
          <DialogDescription>{t('ackSettings.dialogDescription')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <fieldset className="grid gap-1.5">
            <legend className="mb-1 text-sm font-medium">{t('ackSettings.roles')}</legend>
            <div className="grid grid-cols-2 gap-1.5">
              {roles.map((role) => (
                <label key={role} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={chosenRoles.includes(role)}
                    onChange={() => {
                      toggle(chosenRoles, role, setRoles)
                    }}
                  />
                  {roleLabel(role)}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="grid gap-1.5">
            <legend className="mb-1 text-sm font-medium">{t('ackSettings.groups')}</legend>
            {groups.isPending && <LoadingState label={t('ackSettings.loading')} rows={1} />}
            {groups.isError && <InlineError error={groups.error} />}
            {groups.data && groups.data.length === 0 && (
              <p className="text-sm text-muted-foreground">{t('ackSettings.noGroups')}</p>
            )}
            <div className="grid grid-cols-2 gap-1.5">
              {(groups.data ?? []).map((g) => (
                <label key={g.key} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={chosenGroups.includes(g.key)}
                    onChange={() => {
                      toggle(chosenGroups, g.key, setGroups)
                    }}
                  />
                  {g.name}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-units`}>{t('ackSettings.units')}</Label>
            <Input
              id={`${id}-units`}
              value={units}
              onChange={(e) => {
                setUnits(e.target.value)
              }}
              aria-describedby={`${id}-units-hint`}
            />
            <p id={`${id}-units-hint`} className="text-xs text-muted-foreground">
              {t('ackSettings.unitsHint')}
            </p>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-days`}>{t('ackSettings.reminderDays')}</Label>
            <Input
              id={`${id}-days`}
              inputMode="numeric"
              value={days}
              onChange={(e) => {
                setDays(e.target.value)
              }}
              aria-invalid={daysError || undefined}
              aria-describedby={`${id}-days-hint`}
              className="max-w-32"
            />
            <p
              id={`${id}-days-hint`}
              role={daysError ? 'alert' : undefined}
              className={daysError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
            >
              {daysError
                ? t('ackSettings.reminderProblem')
                : t('ackSettings.reminderHint', { days: defaultDays })}
            </p>
          </div>
          {save.isError && <InlineError error={save.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t('common.loading') : t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Who must confirm valid versions per document class, and the reminder interval (DMS-100, ADR
 * 0048), for tenant administrators. Classes without settings inherit from their parent class, then
 * from the tenant default.
 */
export function AcknowledgementSettingsPanel() {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const settings = useQuery(acknowledgementSettingsQuery(client))
  const [editing, setEditing] = useState<Target | undefined>(undefined)

  const remove = useMutation({
    mutationFn: (target: Target) => removeAcknowledgementSettings(client, target.classId),
    onSuccess: async (_, target) => {
      await queryClient.invalidateQueries({ queryKey: ['acknowledgement-settings'] })
      toast(t('ackSettings.removed', { name: target.name }))
    },
  })

  const data = settings.data
  const rows: Target[] = data
    ? [
        { classId: undefined, name: t('ackSettings.tenantDefault'), settings: data.tenant },
        ...(data.classes ?? []).map((c) => ({
          classId: c.classId,
          name: c.label,
          settings: c.settings,
        })),
      ]
    : []

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('ackSettings.title')}</CardTitle>
        <CardDescription>{t('ackSettings.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {settings.isPending && <LoadingState label={t('ackSettings.loading')} />}
        {settings.isError && (
          <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
        )}
        {data && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">{t('ackSettings.title')}</caption>
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th scope="col" className="py-2 pr-3 font-medium">
                    {t('ackSettings.classColumn')}
                  </th>
                  <th scope="col" className="py-2 pr-3 font-medium">
                    {t('ackSettings.recipientsColumn')}
                  </th>
                  <th scope="col" className="py-2 pr-3 font-medium">
                    {t('ackSettings.remindersColumn')}
                  </th>
                  <th scope="col" className="py-2">
                    <span className="sr-only">{t('ackSettings.edit')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.classId ?? 'tenant'} className="border-b last:border-0">
                    <th scope="row" className="py-2 pr-3 text-left font-medium">
                      {row.name}
                    </th>
                    <td className="py-2 pr-3">
                      {row.settings ? (
                        recipientsText(row.settings)
                      ) : (
                        <span className="text-muted-foreground">
                          {row.classId ? t('ackSettings.inherited') : t('ackSettings.everyReader')}
                        </span>
                      )}
                    </td>
                    <td className="py-2 pr-3">
                      {row.settings ? (
                        remindersText(row.settings, data.defaultReminderDays)
                      ) : (
                        <span className="text-muted-foreground">
                          {row.classId
                            ? t('ackSettings.inherited')
                            : t('ackSettings.defaultDays', { days: data.defaultReminderDays })}
                        </span>
                      )}
                    </td>
                    <td className="flex justify-end gap-2 py-2">
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={t('ackSettings.editLabel', { name: row.name })}
                        onClick={() => {
                          setEditing(row)
                        }}
                      >
                        {t('ackSettings.edit')}
                      </Button>
                      {row.settings && (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={remove.isPending}
                          aria-label={
                            row.classId
                              ? t('ackSettings.removeLabel', { name: row.name })
                              : t('ackSettings.removeTenantLabel')
                          }
                          onClick={() => {
                            remove.mutate(row)
                          }}
                        >
                          {row.classId ? t('ackSettings.remove') : t('ackSettings.removeTenant')}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {remove.isError && <InlineError error={remove.error} />}
      </CardContent>
      {editing && data && (
        <SettingsDialog
          target={editing}
          roles={data.roles ?? []}
          defaultDays={data.defaultReminderDays}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      )}
    </Card>
  )
}
