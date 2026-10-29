import { Plus, Trash2 } from 'lucide-react'
import {
  CHECKED,
  parseRows,
  serializeRows,
  UNCHECKED,
  type WordColumn,
  type WordField,
  type WordRow,
} from '@/api/word'
import { selectClass } from '@/components/documents/document-workspace'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { t } from '@/i18n'
import { formatPlainDate } from '@/i18n/format'

type Typed = Pick<WordField, 'type' | 'label' | 'required' | 'options'>

/**
 * The input of a manual Word field by its type (DMS-115, DMS-122): text, date, number, a check box (value
 * `true`/`false`), a select of the field's options (Choice) or a table of rows with one input per column
 * (Rows, a repeating section of the Word file; rows can be added and removed).
 */
export function WordFieldInput({
  id,
  field,
  value,
  onChange,
}: {
  id: string
  field: WordField
  value: string
  onChange: (value: string) => void
}) {
  if (field.type === 'Rows') {
    return <RowsInput id={id} field={field} value={value} onChange={onChange} />
  }
  return <SimpleInput id={id} field={field} value={value} onChange={onChange} />
}

function SimpleInput({
  id,
  field,
  value,
  onChange,
  label,
}: {
  id?: string
  field: Typed
  value: string
  onChange: (value: string) => void
  /** Accessible name when there is no visible label (cells of rows). */
  label?: string
}) {
  if (field.type === 'Checkbox') {
    return (
      <input
        id={id}
        type="checkbox"
        className="size-4 justify-self-start"
        aria-label={label}
        checked={value === CHECKED}
        onChange={(event) => {
          onChange(event.target.checked ? CHECKED : UNCHECKED)
        }}
      />
    )
  }
  if (field.type === 'Choice') {
    const options = field.options ?? []
    return (
      <select
        id={id}
        aria-label={label}
        className={selectClass}
        value={value}
        required={field.required}
        onChange={(event) => {
          onChange(event.target.value)
        }}
      >
        <option value="">{t('word.fields.choose')}</option>
        {/* a value no longer among the options stays visible until it is changed */}
        {value && !options.includes(value) && <option value={value}>{value}</option>}
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    )
  }
  return (
    <Input
      id={id}
      aria-label={label}
      type={field.type === 'Date' ? 'date' : field.type === 'Number' ? 'number' : 'text'}
      value={value}
      required={field.required}
      onChange={(event) => {
        onChange(event.target.value)
      }}
    />
  )
}

function RowsInput({
  id,
  field,
  value,
  onChange,
}: {
  id: string
  field: WordField
  value: string
  onChange: (value: string) => void
}) {
  const columns = field.columns ?? []
  const rows = parseRows(value)
  const set = (next: WordRow[]) => {
    onChange(serializeRows(next))
  }
  return (
    <div id={id} role="group" aria-label={field.label} className="space-y-2">
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('word.fields.noRows')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">{field.label}</caption>
            <thead>
              <tr className="border-b text-left">
                <th scope="col" className="p-1 font-medium">
                  #
                </th>
                {columns.map((c) => (
                  <th key={c.key} scope="col" className="p-1 font-medium">
                    {c.label}
                    {c.required && (
                      <span className="text-destructive" aria-label={t('word.fields.required')}>
                        {' '}
                        *
                      </span>
                    )}
                  </th>
                ))}
                <th scope="col" className="p-1">
                  <span className="sr-only">{t('word.fields.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                // rows have no identity of their own; their position is their key
                <tr key={i} className="border-b last:border-0">
                  <td className="p-1 text-muted-foreground">{i + 1}</td>
                  {columns.map((c) => (
                    <td key={c.key} className="p-1">
                      <SimpleInput
                        // required columns are checked on submission, not when saving
                        field={{ ...c, required: false }}
                        value={row[c.key] ?? ''}
                        label={t('word.fields.cell', { column: c.label, row: i + 1 })}
                        onChange={(v) => {
                          set(rows.map((r, j) => (j === i ? { ...r, [c.key]: v } : r)))
                        }}
                      />
                    </td>
                  ))}
                  <td className="p-1">
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      aria-label={t('word.fields.removeRow', { row: i + 1 })}
                      onClick={() => {
                        set(rows.filter((_, j) => j !== i))
                      }}
                    >
                      <Trash2 className="size-4" aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={columns.length === 0}
        onClick={() => {
          set([...rows, {}])
        }}
      >
        <Plus className="size-4" aria-hidden />
        {t('word.fields.addRow')}
      </Button>
    </div>
  )
}

/** A value as shown read-only: dates in the UI language, check boxes as yes/no, rows as a table. */
export function WordFieldValue({ field, value }: { field: WordField; value: string | undefined }) {
  if (!value) return <span className="text-muted-foreground">{t('word.fields.notSet')}</span>
  if (field.type === 'Rows' && field.source === 'Manual') {
    const columns = field.columns ?? []
    const rows = parseRows(value)
    return (
      <table className="w-full text-sm">
        <caption className="sr-only">{field.label}</caption>
        <thead>
          <tr className="border-b text-left">
            {columns.map((c) => (
              <th key={c.key} scope="col" className="p-1 font-medium">
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="border-b last:border-0">
              {columns.map((c) => (
                <td key={c.key} className="p-1">
                  {plain(c, row[c.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    )
  }
  return <>{field.source === 'Manual' ? plain(field, value) : value}</>
}

function plain(field: Pick<WordColumn, 'type'>, value: string | undefined): string {
  if (!value) return ''
  if (field.type === 'Checkbox')
    return value === CHECKED ? t('word.fields.yes') : t('word.fields.no')
  if (field.type === 'Date' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return formatPlainDate(value)
  return value
}
