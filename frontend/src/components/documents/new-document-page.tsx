import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { catalogQuery, templateFieldsQuery, type DocumentLanguage } from '@/api/templates'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  DocumentWorkspace,
  LanguageSelect,
  selectClass,
  type DocumentWorkspaceProps,
} from './document-workspace'

type EditorProps = Pick<
  DocumentWorkspaceProps,
  'templateId' | 'version' | 'language' | 'draft' | 'onSubmitted'
>

/** Loads the fields of the template version and shows the workspace for them. */
export function DocumentEditor(props: EditorProps) {
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

interface NewDocumentPageProps {
  /** Called after the new draft was submitted, e.g. to open the document page. */
  onSubmitted?: (documentId: string) => void
}

/** "New document": choose a released template, fill in its fields and watch the live preview (US-03.2). */
export function NewDocumentPage({ onSubmitted }: NewDocumentPageProps = {}) {
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
        <LanguageSelect value={language} onChange={setLanguage} />
      </div>
      {entry && (
        <DocumentEditor
          key={selected}
          templateId={entry.templateId}
          version={entry.version}
          language={language}
          onSubmitted={onSubmitted}
        />
      )}
    </div>
  )
}
