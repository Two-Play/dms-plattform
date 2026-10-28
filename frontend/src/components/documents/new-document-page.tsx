import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useApiClient } from '@/api/context'
import { applyReference, createDocument, removeReference, updateDraft } from '@/api/documents'
import {
  partnerAttribute,
  type DocumentVersionView,
  type Partner,
  type ReferenceField,
} from '@/api/partners'
import {
  catalogQuery,
  previewQuery,
  templateFieldsQuery,
  type DocumentLanguage,
  type TemplateField,
} from '@/api/templates'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { DocumentForm } from './document-form'
import { DocumentPreview } from './document-preview'
import { PartnerPicker } from './partner-picker'
import {
  collectValues,
  fieldControlId,
  initialInputs,
  type FieldInput,
  type FormInputs,
} from './field-values'

/** Delay after the last change before the preview is requested. */
export const PREVIEW_DEBOUNCE_MS = 300

const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

function focusField(key: string) {
  document.getElementById(fieldControlId(key))?.focus()
}

interface EditorProps {
  templateId: string
  version: number
  language: DocumentLanguage
}

/** What the last save left on the server: the draft and the entries taken over into it. */
interface Saved {
  documentId: string
  label: string
  applied: Record<string, string>
}

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved'; label: string; warning?: string }
  | { kind: 'failed'; message: string }

/** Form and live preview for a loaded template version. */
function DocumentWorkspace({
  templateId,
  version,
  language,
  fields,
  references,
}: EditorProps & { fields: TemplateField[]; references: ReferenceField[] }) {
  const client = useApiClient()
  const [inputs, setInputs] = useState<FormInputs>(() => initialInputs(fields))
  const [picked, setPicked] = useState<Record<string, Partner | undefined>>({})
  const [saved, setSaved] = useState<Saved | undefined>(undefined)
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' })
  // Fields filled by a reference field show the picked entry's values and are not entered.
  const formInputs = useMemo(() => {
    const result = { ...inputs }
    for (const reference of references) {
      for (const mapping of reference.fields ?? []) {
        const partner = picked[reference.key]
        result[mapping.field] = partner ? partnerAttribute(partner, mapping.attribute) : ''
      }
    }
    return result
  }, [inputs, picked, references])
  const { values, errors } = useMemo(() => collectValues(fields, formInputs), [fields, formInputs])
  const sent = useDebouncedValue(values, PREVIEW_DEBOUNCE_MS)
  const preview = useQuery({
    ...previewQuery(client, templateId, version, sent, language),
    placeholderData: keepPreviousData,
  })

  // Backend issues apply to the values that were sent; show them while the field still has that value.
  const serverErrors: Record<string, string> = {}
  for (const issue of preview.data?.issues ?? []) {
    const key = issue.field
    if (key !== undefined && JSON.stringify(sent[key]) === JSON.stringify(values[key])) {
      serverErrors[key] = issue.message
    }
  }

  const save = async () => {
    const title = values.title?.type === 'Text' ? values.title.value : ''
    if (title === '') {
      setSaveState({ kind: 'failed', message: 'Enter a title before saving the draft.' })
      return
    }
    // Values taken over from the partner master are set by the server, not sent as entered values.
    const filled = new Set(fields.filter((f) => f.reference).map((f) => f.key))
    const entered = Object.fromEntries(Object.entries(values).filter(([key]) => !filled.has(key)))
    setSaveState({ kind: 'saving' })
    let current = saved
    try {
      if (current) {
        const draft = await updateDraft(client, current.documentId, { title, values: entered })
        current = { ...current, label: draft.version }
      } else {
        const created = await createDocument(client, {
          templateId,
          templateVersion: version,
          title,
          values: entered,
        })
        current = { documentId: created.id, label: created.current.version, applied: {} }
      }
      setSaved(current)
    } catch (e) {
      setSaveState({ kind: 'failed', message: e instanceof Error ? e.message : String(e) })
      return
    }
    // The draft is saved; taking the partners over may still fail while the partner master is down.
    const warnings: string[] = []
    for (const reference of references) {
      const partner = picked[reference.key]
      const applied = current.applied[reference.key]
      if (partner?.id === applied) continue
      try {
        const draft: DocumentVersionView = partner
          ? await applyReference(client, current.documentId, reference.key, partner.id)
          : await removeReference(client, current.documentId, reference.key)
        const others = Object.entries(current.applied).filter(([key]) => key !== reference.key)
        const nextApplied: Record<string, string> = Object.fromEntries(
          partner ? [...others, [reference.key, partner.id]] : others,
        )
        current = { ...current, label: draft.version, applied: nextApplied }
        setSaved(current)
      } catch (e) {
        warnings.push(
          `${reference.label} was not taken over: ${e instanceof Error ? e.message : String(e)}`,
        )
      }
    }
    setSaveState({
      kind: 'saved',
      label: current.label,
      warning: warnings.length > 0 ? warnings.join(' ') : undefined,
    })
  }

  return (
    // Stacked on narrow screens; side by side from lg, the preview wider than the form. There the preview
    // stays in view and scrolls on its own, so a twelve-page document does not push the form away.
    <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <div className="min-w-0 space-y-4">
        {references.length > 0 && (
          <fieldset className="space-y-3">
            <legend className="mb-2 font-heading text-sm font-semibold">
              From the partner master
            </legend>
            {references.map((reference) => (
              <PartnerPicker
                key={reference.key}
                reference={reference}
                selected={picked[reference.key]}
                onSelect={(partner) => {
                  setPicked((current) => ({ ...current, [reference.key]: partner }))
                }}
              />
            ))}
          </fieldset>
        )}
        <DocumentForm
          fields={fields}
          inputs={formInputs}
          errors={{ ...serverErrors, ...errors }}
          onChange={(key: string, input: FieldInput) => {
            setInputs((current) => ({ ...current, [key]: input }))
          }}
        />
        <div className="flex flex-wrap items-center gap-3 border-t pt-4">
          <Button
            type="button"
            disabled={saveState.kind === 'saving'}
            aria-describedby="save-draft-note"
            onClick={() => {
              void save()
            }}
          >
            {saveState.kind === 'saving' ? 'Saving…' : 'Save draft'}
          </Button>
          <div id="save-draft-note" className="space-y-1 text-xs" aria-live="polite">
            {saveState.kind === 'idle' && (
              <p className="text-muted-foreground">
                Saves the entered values as a draft; you can continue later.
              </p>
            )}
            {saveState.kind === 'saved' && <p>Draft saved as version {saveState.label}.</p>}
            {saveState.kind === 'saved' && saveState.warning && (
              <p role="alert" className="text-destructive">
                {saveState.warning}
              </p>
            )}
            {saveState.kind === 'failed' && (
              <p role="alert" className="text-destructive">
                {saveState.message}
              </p>
            )}
          </div>
        </div>
      </div>
      <div className="min-w-0 lg:sticky lg:top-20 lg:max-h-[calc(100svh-6rem)] lg:self-start lg:overflow-y-auto">
        <DocumentPreview
          preview={preview.data}
          updating={preview.isFetching}
          error={preview.error}
          onFocusField={focusField}
        />
      </div>
    </div>
  )
}

function DocumentEditor(props: EditorProps) {
  const client = useApiClient()
  const fields = useQuery(templateFieldsQuery(client, props.templateId, props.version))
  if (fields.isPending) return <Skeleton className="h-64 w-full" />
  if (fields.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {fields.error.message}
      </p>
    )
  }
  return (
    <DocumentWorkspace
      {...props}
      fields={fields.data.fields ?? []}
      references={fields.data.references ?? []}
    />
  )
}

/** "New document": choose a released template, fill in its fields and watch the live preview (US-03.2). */
export function NewDocumentPage() {
  const client = useApiClient()
  const catalog = useQuery(catalogQuery(client))
  const [selected, setSelected] = useState('')
  const [language, setLanguage] = useState<DocumentLanguage>('en')
  const entry = catalog.data?.find((e) => `${e.templateId}:${e.version}` === selected)

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="font-heading text-2xl font-semibold">New document</h1>
        <p className="text-sm text-muted-foreground">
          Choose a template, fill in its fields and check the result in the live preview.
        </p>
      </div>
      <div className="grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)] lg:max-w-3xl">
        <div className="space-y-1.5">
          <Label htmlFor="template">Template</Label>
          <select
            id="template"
            className={selectClass}
            value={selected}
            disabled={!catalog.data}
            onChange={(e) => {
              setSelected(e.target.value)
            }}
          >
            <option value="">
              {catalog.isPending ? 'Loading templates…' : 'Choose a template…'}
            </option>
            {catalog.data?.map((e) => (
              <option key={`${e.templateId}:${e.version}`} value={`${e.templateId}:${e.version}`}>
                {e.name} (version {e.version})
              </option>
            ))}
          </select>
          {entry?.description && (
            <p className="text-xs text-muted-foreground">{entry.description}</p>
          )}
          {catalog.isError && (
            <p role="alert" className="text-xs text-destructive">
              {catalog.error.message}
            </p>
          )}
          {catalog.data?.length === 0 && (
            <p className="text-xs text-muted-foreground">No released templates yet.</p>
          )}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="language">Document language</Label>
          <select
            id="language"
            className={selectClass}
            value={language}
            onChange={(e) => {
              setLanguage(e.target.value === 'de' ? 'de' : 'en')
            }}
          >
            <option value="en">English</option>
            <option value="de">German</option>
          </select>
        </div>
      </div>
      {entry && (
        <DocumentEditor
          key={selected}
          templateId={entry.templateId}
          version={entry.version}
          language={language}
        />
      )}
    </div>
  )
}
