import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useApiClient } from '@/api/context'
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

/** Form and live preview for a loaded template version. */
function DocumentWorkspace({
  templateId,
  version,
  language,
  fields,
}: EditorProps & { fields: TemplateField[] }) {
  const client = useApiClient()
  const [inputs, setInputs] = useState<FormInputs>(() => initialInputs(fields))
  const { values, errors } = useMemo(() => collectValues(fields, inputs), [fields, inputs])
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

  return (
    // Stacked on narrow screens; side by side from lg, the preview wider than the form. There the preview
    // stays in view and scrolls on its own, so a twelve-page document does not push the form away.
    <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <div className="min-w-0 space-y-4">
        <DocumentForm
          fields={fields}
          inputs={inputs}
          errors={{ ...serverErrors, ...errors }}
          onChange={(key: string, input: FieldInput) => {
            setInputs((current) => ({ ...current, [key]: input }))
          }}
        />
        <div className="flex flex-wrap items-center gap-3 border-t pt-4">
          <Button type="button" disabled aria-describedby="save-draft-note">
            Save draft
          </Button>
          <p id="save-draft-note" className="text-xs text-muted-foreground">
            Saving comes with document management (DMS-37); the preview is not stored.
          </p>
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
  return <DocumentWorkspace {...props} fields={fields.data.fields ?? []} />
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
