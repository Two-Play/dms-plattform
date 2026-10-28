import type { TemplateField } from '@/api/templates'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { ALLOWED_GROUPS_FIELD, groupKeys, groupsInput, visibleFields } from './access-groups'
import { fieldControlId, type FieldInput, type FormInputs } from './field-values'
import { GroupPicker } from './group-picker'

const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30'

interface FieldProps {
  field: TemplateField
  input: FieldInput | undefined
  error: string | undefined
  onChange: (input: FieldInput) => void
}

function FieldControl({ field, input, error, onChange }: FieldProps) {
  const id = fieldControlId(field.key)
  const describedBy = [
    field.reference ? `${id}-origin` : undefined,
    field.description ? `${id}-description` : undefined,
    error ? `${id}-error` : undefined,
  ]
    .filter(Boolean)
    .join(' ')
  const common = {
    id,
    'aria-required': field.required || undefined,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy || undefined,
  }
  const fieldType = field.fieldType
  const text = typeof input === 'string' ? input : ''

  if (field.reference) {
    return (
      <Input
        {...common}
        readOnly
        aria-readonly="true"
        className="bg-muted/50"
        placeholder="Pick a partner above"
        value={text}
      />
    )
  }

  if (field.key === ALLOWED_GROUPS_FIELD) {
    return (
      <GroupPicker
        id={id}
        value={groupKeys(input)}
        invalid={Boolean(error)}
        describedBy={describedBy || undefined}
        onChange={(keys) => {
          onChange(groupsInput(keys))
        }}
      />
    )
  }

  if (field.multiple && fieldType.type === 'Selection') {
    const selected = Array.isArray(input) ? input : []
    const options = (fieldType.options ?? []).filter((o) => o.active)
    return (
      <div className="flex flex-wrap gap-x-4 gap-y-1" role="group" aria-labelledby={`${id}-label`}>
        {options.map((option, index) => (
          <label key={option.code} className="flex items-center gap-1.5 text-sm">
            <input
              type="checkbox"
              id={index === 0 ? id : undefined}
              checked={selected.includes(option.code)}
              onChange={(e) => {
                onChange(
                  e.target.checked
                    ? [...selected, option.code]
                    : selected.filter((c) => c !== option.code),
                )
              }}
            />
            {option.label}
          </label>
        ))}
      </div>
    )
  }
  if (field.multiple) {
    return (
      <Textarea
        {...common}
        rows={3}
        placeholder="One value per line"
        value={text}
        onChange={(e) => {
          onChange(e.target.value)
        }}
      />
    )
  }
  switch (fieldType.type) {
    case 'Amount': {
      const amount =
        typeof input === 'object' && !Array.isArray(input) ? input : { amount: '', currency: 'EUR' }
      const currencies = fieldType.currencies ?? []
      return (
        <div className="flex gap-2">
          <Input
            {...common}
            inputMode="decimal"
            value={amount.amount}
            onChange={(e) => {
              onChange({ ...amount, amount: e.target.value })
            }}
          />
          {currencies.length > 0 ? (
            <select
              aria-label={`${field.label} currency`}
              className={`${selectClass} w-24`}
              value={amount.currency}
              onChange={(e) => {
                onChange({ ...amount, currency: e.target.value })
              }}
            >
              {currencies.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          ) : (
            <Input
              aria-label={`${field.label} currency`}
              className="w-24"
              maxLength={3}
              value={amount.currency}
              onChange={(e) => {
                onChange({ ...amount, currency: e.target.value.toUpperCase() })
              }}
            />
          )}
        </div>
      )
    }
    case 'Selection':
      return (
        <select
          {...common}
          className={selectClass}
          value={text}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        >
          <option value="">Select…</option>
          {(fieldType.options ?? [])
            .filter((o) => o.active || o.code === text)
            .map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
        </select>
      )
    case 'YesNo':
      return (
        <select
          {...common}
          className={selectClass}
          value={text}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        >
          <option value="">Select…</option>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      )
    case 'Date':
      return (
        <Input
          {...common}
          type="date"
          min={fieldType.min}
          max={fieldType.max}
          value={text}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )
    case 'Number':
      return (
        <Input
          {...common}
          inputMode="decimal"
          value={text}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )
    default:
      return (
        <Input
          {...common}
          value={text}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )
  }
}

interface DocumentFormProps {
  fields: TemplateField[]
  inputs: FormInputs
  /** Validation messages by field key: the form's own checks first, then the backend's. */
  errors: Record<string, string>
  onChange: (key: string, input: FieldInput) => void
}

function FieldRow({ field, input, error, onChange }: FieldProps) {
  const id = fieldControlId(field.key)
  return (
    <div className="space-y-1.5" data-field-row={field.key}>
      <Label id={`${id}-label`} htmlFor={id}>
        {field.label}
        {field.required && (
          <span className="text-destructive" aria-hidden="true" title="Required">
            *
          </span>
        )}
      </Label>
      {field.reference && (
        <p id={`${id}-origin`} className="text-xs text-muted-foreground">
          From partner master – taken over from the system, not editable
        </p>
      )}
      <FieldControl field={field} input={input} error={error} onChange={onChange} />
      {field.description && (
        <p id={`${id}-description`} className="text-xs text-muted-foreground">
          {field.description}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

/** Form generated from the fields of a template version: inputs by type, required markers, inline errors. */
export function DocumentForm({ fields, inputs, errors, onChange }: DocumentFormProps) {
  const shown = visibleFields(fields, inputs)
  const used = shown.filter((f) => f.usedInTemplate)
  const other = shown.filter((f) => !f.usedInTemplate)
  const rows = (group: TemplateField[]) =>
    group.map((field) => (
      <FieldRow
        key={field.key}
        field={field}
        input={inputs[field.key]}
        error={errors[field.key]}
        onChange={(input) => {
          onChange(field.key, input)
        }}
      />
    ))

  return (
    <form
      className="space-y-6"
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
      }}
    >
      <p className="text-xs text-muted-foreground">
        Fields marked <span className="text-destructive">*</span> are required before the document
        can be approved.
      </p>
      {used.length > 0 && (
        <fieldset className="space-y-4">
          <legend className="mb-2 font-heading text-sm font-semibold">Template fields</legend>
          {rows(used)}
        </fieldset>
      )}
      {other.length > 0 && (
        <fieldset className="space-y-4">
          <legend className="mb-2 font-heading text-sm font-semibold">Further metadata</legend>
          {rows(other)}
        </fieldset>
      )}
    </form>
  )
}
