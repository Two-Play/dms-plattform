import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useApiClient } from '@/api/context'
import { catalogQuery, templateFieldsQuery, type DocumentLanguage } from '@/api/templates'
import { Label } from '@/components/ui/label'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { t } from '@/i18n'
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

interface NewDocumentPageProps {
  /** Called after the new draft was submitted, e.g. to open the document page. */
  onSubmitted?: (documentId: string) => void
  /** Trail to the page, set by the route. */
  breadcrumbs?: ReactNode
}

/** "New document": choose a released template, fill in its fields and watch the live preview (US-03.2). */
export function NewDocumentPage({ onSubmitted, breadcrumbs }: NewDocumentPageProps = {}) {
  const client = useApiClient()
  const catalog = useQuery(catalogQuery(client))
  const [selected, setSelected] = useState('')
  const [language, setLanguage] = useState<DocumentLanguage>('en')
  const entry = catalog.data?.find((e) => `${e.templateId}:${e.version}` === selected)

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
              setSelected(e.target.value)
            }}
          >
            <option value="">
              {catalog.isPending ? t('docs.new.loadingTemplates') : t('docs.new.chooseTemplate')}
            </option>
            {catalog.data?.map((e) => (
              <option key={`${e.templateId}:${e.version}`} value={`${e.templateId}:${e.version}`}>
                {t('docs.new.option', { name: e.name, version: e.version })}
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
