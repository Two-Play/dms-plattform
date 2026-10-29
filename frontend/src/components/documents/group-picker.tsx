import { useGroups } from '@/api/groups'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'

interface GroupPickerProps {
  id: string
  /** Selected group keys. */
  value: string[]
  onChange: (keys: string[]) => void
  invalid: boolean
  describedBy: string | undefined
}

/**
 * Picks the access groups allowed to see a confidential document (DMS-101): one checkbox per group of the
 * tenant. Keys that are no group of the tenant stay listed so they can be removed. Without the group list
 * (e.g. the request failed), the keys are entered one per line.
 */
export function GroupPicker({ id, value, onChange, invalid, describedBy }: GroupPickerProps) {
  const groups = useGroups()

  if (groups.isPending) return <Skeleton className="h-16 w-full" />
  if (groups.isError) {
    return (
      <div className="space-y-1">
        <Textarea
          id={id}
          rows={3}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          placeholder={t('docs.groups.perLine')}
          value={value.join('\n')}
          onChange={(e) => {
            onChange(
              e.target.value
                .split('\n')
                .map((line) => line.trim())
                .filter((line, index, all) => line !== '' || index === all.length - 1),
            )
          }}
        />
        <p className="text-xs text-muted-foreground">
          {t('docs.groups.loadFailed', { message: groups.error.message })}
        </p>
      </div>
    )
  }

  const known = new Set(groups.data.map((g) => g.key))
  const entries = [
    ...groups.data.map((g) => ({ key: g.key, name: g.name, unknown: false })),
    ...value.filter((k) => !known.has(k)).map((k) => ({ key: k, name: k, unknown: true })),
  ]
  if (entries.length === 0) {
    return (
      <p id={id} className="text-sm text-muted-foreground" aria-describedby={describedBy}>
        {t('docs.groups.none')}
      </p>
    )
  }
  return (
    <div
      className="flex flex-wrap gap-x-4 gap-y-1"
      role="group"
      aria-labelledby={`${id}-label`}
      aria-describedby={describedBy}
      aria-invalid={invalid || undefined}
    >
      {entries.map((entry, index) => (
        <label key={entry.key} className="flex items-center gap-1.5 text-sm">
          <input
            type="checkbox"
            id={index === 0 ? id : undefined}
            checked={value.includes(entry.key)}
            onChange={(e) => {
              onChange(
                e.target.checked ? [...value, entry.key] : value.filter((k) => k !== entry.key),
              )
            }}
          />
          {entry.name}
          {entry.unknown && (
            <span className="text-xs text-destructive">{t('docs.groups.unknown')}</span>
          )}
        </label>
      ))}
    </div>
  )
}
