import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useApiClient } from '@/api/context'
import { catalogQuery, templateFieldsQuery, type DocumentLanguage } from '@/api/templates'
import { DiscardDialog } from '@/components/templates/editor/unsaved-changes'
import { wordTemplateCatalogQuery } from '@/api/word'
import { Card, CardContent } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { getLocale, t } from '@/i18n'
import {
  DocumentWorkspace,
  LanguageSelect,
  selectClass,
  type DocumentWorkspaceProps,
} from './document-workspace'

type EditorProps = Pick<
  DocumentWorkspaceProps,
  | 'templateId'
  | 'version'
  | 'language'
  | 'draft'
  | 'onSubmitted'
  | 'kind'
  | 'aside'
  | 'onCreated'
  | 'afterCreate'
  | 'initialValues'
  | 'unsavedSection'
  | 'onDirtyChange'
>

/** Loads the fields of the template version and shows the workspace for them. */
export function DocumentEditor(props: EditorProps) {
  const client = useApiClient()
  const fields = useQuery(templateFieldsQuery(client, props.templateId, props.version))
  if (fields.isPending) return <LoadingState label={t('docs.new.loadingFields')} rows={6} />
  if (fields.isError) {
    return <ErrorState error={fields.error} onRetry={() => void fields.refetch()} />
  }
  return (
    <DocumentWorkspace
      {...props}
      fields={fields.data.fields ?? []}
      references={fields.data.references ?? []}
    />
  )
}

/** A change of the template or of what the content is. */
interface Choice {
  selected?: string
  ownFile?: boolean
}

interface NewDocumentPageProps {
  /** Called after the new draft was submitted, e.g. to open the document page. */
  onSubmitted?: (documentId: string) => void
  /** Called when a file or Word document was created: it continues on its document page (DMS-115). */
  onCreated?: (documentId: string) => void
  /** Trail to the page, set by the route. */
  breadcrumbs?: ReactNode
}

/**
 * "New document": choose a released template, fill in its fields and watch the live preview (US-03.2). A
 * Word template (DMS-115) starts the document with its Word file; a structured template can also be used for
 * a file document whose file is uploaded after saving.
 */
export function NewDocumentPage({
  onSubmitted,
  onCreated,
  breadcrumbs,
}: NewDocumentPageProps = {}) {
  const client = useApiClient()
  const catalog = useQuery(catalogQuery(client))
  const words = useQuery(wordTemplateCatalogQuery(client))
  const [selected, setSelected] = useState('')
  const [language, setLanguage] = useState<DocumentLanguage>(getLocale)
  const [ownFile, setOwnFile] = useState(false)
  const [dirty, setDirty] = useState(false)
  // Another template or kind starts an empty form: with unsaved input the change waits for an answer.
  const [pending, setPending] = useState<Choice | undefined>(undefined)
  const apply = (choice: Choice) => {
    if (choice.selected !== undefined) setSelected(choice.selected)
    if (choice.ownFile !== undefined) setOwnFile(choice.ownFile)
  }
  const choose = (choice: Choice) => {
    if (dirty) setPending(choice)
    else apply(choice)
  }
  const entry = catalog.data?.find((e) => `${e.templateId}:${e.version}` === selected)
  const wordKeys = new Set((words.data ?? []).map((w) => `${w.templateId}:${String(w.version)}`))
  const isWord = entry !== undefined && wordKeys.has(selected)
  const kind = isWord ? 'word' : ownFile ? 'file' : 'structured'

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('docs.new.title')}
        description={t('docs.new.description')}
        breadcrumbs={breadcrumbs}
      />
      <div className="grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)] lg:max-w-3xl">
        <div className="space-y-1.5">
          <Label htmlFor="template">{t('docs.new.template')}</Label>
          <select
            id="template"
            className={selectClass}
            value={selected}
            disabled={!catalog.data}
            onChange={(e) => {
              choose({ selected: e.target.value })
            }}
          >
            <option value="">
              {catalog.isPending ? t('docs.new.loadingTemplates') : t('docs.new.chooseTemplate')}
            </option>
            {catalog.data?.map((e) => (
              <option key={`${e.templateId}:${e.version}`} value={`${e.templateId}:${e.version}`}>
                {wordKeys.has(`${e.templateId}:${String(e.version)}`)
                  ? t('word.new.wordOption', { name: e.name, version: e.version })
                  : t('docs.new.option', { name: e.name, version: e.version })}
              </option>
            ))}
          </select>
          {entry?.description && (
            <p className="text-xs text-muted-foreground">{entry.description}</p>
          )}
          {catalog.isError && (
            <ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} />
          )}
          {catalog.data?.length === 0 && (
            <p className="text-xs text-muted-foreground">{t('docs.new.noTemplates')}</p>
          )}
        </div>
        <LanguageSelect value={language} onChange={setLanguage} />
      </div>
      {entry && !isWord && (
        <fieldset className="space-y-1.5 text-sm">
          <legend className="font-medium">{t('word.new.content')}</legend>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="content-kind"
              checked={!ownFile}
              onChange={() => {
                choose({ ownFile: false })
              }}
            />
            {t('word.new.structured')}
          </label>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="content-kind"
              checked={ownFile}
              onChange={() => {
                choose({ ownFile: true })
              }}
            />
            {t('word.new.file')}
          </label>
        </fieldset>
      )}
      {entry && (
        <DocumentEditor
          key={`${selected}:${kind}`}
          templateId={entry.templateId}
          version={entry.version}
          language={language}
          onSubmitted={onSubmitted}
          kind={kind}
          onCreated={onCreated}
          onDirtyChange={setDirty}
          aside={
            <Card>
              <CardContent className="pt-6 text-sm text-muted-foreground">
                {kind === 'word' ? t('word.new.wordHint') : t('word.new.fileHint')}
              </CardContent>
            </Card>
          }
        />
      )}
      <DiscardDialog
        open={pending !== undefined}
        title={t('docs.new.discard.title')}
        text={t('docs.new.discard.text')}
        stay={t('docs.new.discard.keep')}
        discard={t('docs.new.discard.confirm')}
        onStay={() => {
          setPending(undefined)
        }}
        onDiscard={() => {
          if (pending) apply(pending)
          setPending(undefined)
        }}
      />
    </div>
  )
}
