import { useGroups } from '@/api/groups'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'

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
          placeholder="One group key per line"
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
          The list of groups could not be loaded ({groups.error.message}); enter the group keys.
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
        There are no access groups yet. A tenant administrator creates them.
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
          {entry.unknown && <span className="text-xs text-destructive">(unknown group)</span>}
        </label>
      ))}
    </div>
  )
}
