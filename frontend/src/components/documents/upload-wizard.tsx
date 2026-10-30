import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { useApiClient } from '@/api/context'
import { extensionOf } from '@/api/attachments'
import { hasRole, useMe } from '@/api/me'
import type { DocumentVersionView } from '@/api/partners'
import { createTemplate, templateClassesQuery } from '@/api/template-admin'
import type { DocumentLanguage } from '@/api/templates'
import {
  analyseUpload,
  previewUpload,
  uploadClassesQuery,
  uploadTemplateOf,
  type UploadAnalysis,
  type UploadTemplate,
} from '@/api/uploads'
import {
  CONVERTIBLE_EXTENSIONS,
  saveWordValues,
  uploadOriginal,
  uploadTemplateWord,
  type UploadFile,
  type WordField,
  type WordTemplate,
} from '@/api/word'
import { PageHeader } from '@/components/shared/page-header'
import { LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getLocale, t } from '@/i18n'
import { formatFileSize } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { cn } from 'cn'
import { LanguageSelect, selectClass } from './document-workspace'
import { DocumentEditor } from './new-document-page'
import { WordFieldHelp } from './word-field-help'
import { WordFieldInput, WordFieldValue } from './word-field-input'

const STEPS = ['file', 'fields', 'preview', 'purpose'] as const
type Step = (typeof STEPS)[number]
type Purpose = 'document' | 'template'

const convertible = new Set<string>(CONVERTIBLE_EXTENSIONS)

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** The file name without its extension, as default title or template name. */
function baseName(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

export interface UploadWizardProps {
  /** Trail to the page, set by the route. */
  breadcrumbs?: ReactNode
  /** Called once the document was created with the file, e.g. to open its page. */
  onDocumentCreated?: (documentId: string) => void
  /** Called once the Word template was created, e.g. to open its page. */
  onTemplateCreated?: (templateId: string) => void
  /** Replaceable in tests: stores the original of the new document. */
  uploadFile?: (documentId: string) => UploadFile<DocumentVersionView>
  /** Replaceable in tests: stores the Word file of the new template. */
  uploadTemplate?: (templateId: string, version: number) => UploadFile<WordTemplate>
}

/**
 * "Upload document" (DMS-127, ADR 0049): a wizard in four steps. (1) Choose a file: its fields are read,
 * values a filled document carries are taken over; nothing is stored. (2) Fill the manual fields; the
 * automatic ones are filled by the platform. (3) Check the PDF preview. (4) Decide what it becomes: a document
 * of a class (title and metadata, then the normal lifecycle) or a Word template (designers only, four-eyes
 * release).
 */
export function UploadWizard({
  breadcrumbs,
  onDocumentCreated,
  onTemplateCreated,
  uploadFile,
  uploadTemplate,
}: UploadWizardProps) {
  const client = useApiClient()
  const me = useMe()
  const [step, setStep] = useState<Step>('file')
  const [file, setFile] = useState<File | undefined>(undefined)
  const [analysis, setAnalysis] = useState<UploadAnalysis | undefined>(undefined)
  const [values, setValues] = useState<Record<string, string>>({})
  const heading = useRef<HTMLHeadingElement>(null)
  const first = useRef(true)
  const language: DocumentLanguage = getLocale() === 'de' ? 'de' : 'en'

  // Each step moves the focus to its heading, so screen readers announce it.
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    heading.current?.focus()
  }, [step])

  const analyse = useMutation({
    mutationFn: (chosen: File) => analyseUpload(client, chosen, language),
    onSuccess: (result, chosen) => {
      setFile(chosen)
      setAnalysis(result)
      setValues(
        Object.fromEntries(
          (result.fields ?? [])
            .filter((f) => f.source === 'Manual')
            .map((f) => [f.key, f.value ?? '']),
        ),
      )
      setStep('fields')
    },
  })

  const index = STEPS.indexOf(step)
  const go = (to: Step) => {
    setStep(to)
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('upload.title')}
        description={t('upload.description')}
        breadcrumbs={breadcrumbs}
      />
      <Stepper step={step} reachable={(s) => STEPS.indexOf(s) < index} onGo={go} />
      <p className="sr-only" aria-live="polite">
        {t('upload.steps.current', {
          step: index + 1,
          total: STEPS.length,
          label: t(`upload.steps.${step}`),
        })}
      </p>
      {step === 'file' && (
        <FileStep
          heading={heading}
          busy={analyse.isPending}
          error={analyse.error ? message(analyse.error) : undefined}
          onChoose={(chosen) => {
            analyse.mutate(chosen)
          }}
          chosen={file}
          onNext={
            analysis
              ? () => {
                  go('fields')
                }
              : undefined
          }
        />
      )}
      {step !== 'file' && file && analysis && <FileSummary file={file} analysis={analysis} />}
      {step === 'fields' && analysis && (
        <FieldsStep
          heading={heading}
          analysis={analysis}
          values={values}
          onChange={(key, value) => {
            setValues((current) => ({ ...current, [key]: value }))
          }}
          onBack={() => {
            go('file')
          }}
          onNext={() => {
            go('preview')
          }}
        />
      )}
      {step === 'preview' && file && (
        <PreviewStep
          heading={heading}
          file={file}
          values={values}
          language={language}
          onBack={() => {
            go('fields')
          }}
          onNext={() => {
            go('purpose')
          }}
        />
      )}
      {step === 'purpose' && file && analysis && (
        <PurposeStep
          heading={heading}
          file={file}
          analysis={analysis}
          values={values}
          canDocument={hasRole(me.data, 'Author') || hasRole(me.data, 'Owner')}
          canTemplate={hasRole(me.data, 'TemplateDesigner')}
          onBack={() => {
            go('preview')
          }}
          onDocumentCreated={onDocumentCreated}
          onTemplateCreated={onTemplateCreated}
          uploadFile={uploadFile}
          uploadTemplate={uploadTemplate}
        />
      )}
    </div>
  )
}

/** The steps as an ordered list; earlier steps can be revisited. */
function Stepper({
  step,
  reachable,
  onGo,
}: {
  step: Step
  reachable: (s: Step) => boolean
  onGo: (s: Step) => void
}) {
  const current = STEPS.indexOf(step)
  return (
    <nav aria-label={t('upload.steps.label')}>
      <ol className="flex flex-wrap gap-2 text-sm">
        {STEPS.map((s, i) => {
          const done = i < current
          const content = (
            <>
              <span
                aria-hidden
                className={cn(
                  'flex size-6 items-center justify-center rounded-full border text-xs',
                  i === current && 'border-primary bg-primary text-primary-foreground',
                  done && 'border-primary text-primary',
                )}
              >
                {done ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span className={cn(i === current && 'font-medium')}>{t(`upload.steps.${s}`)}</span>
              {done && <span className="sr-only">({t('upload.steps.done')})</span>}
            </>
          )
          return (
            <li
              key={s}
              aria-current={i === current ? 'step' : undefined}
              className="flex items-center gap-2"
            >
              {reachable(s) ? (
                <button
                  type="button"
                  className="flex items-center gap-2 rounded-md px-1 underline-offset-4 hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
                  onClick={() => {
                    onGo(s)
                  }}
                >
                  {content}
                </button>
              ) : (
                <span className="flex items-center gap-2 px-1">{content}</span>
              )}
              {i < STEPS.length - 1 && (
                <span aria-hidden className="text-muted-foreground">
                  ›
                </span>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

function StepHeading({
  heading,
  children,
}: {
  heading: RefObject<HTMLHeadingElement | null>
  children: ReactNode
}) {
  return (
    <CardTitle>
      <h2 ref={heading} tabIndex={-1} className="outline-none">
        {children}
      </h2>
    </CardTitle>
  )
}

function Navigation({
  onBack,
  onNext,
  nextLabel,
}: {
  onBack?: () => void
  onNext?: () => void
  nextLabel?: string
}) {
  return (
    <div className="flex flex-wrap gap-2 border-t pt-4">
      {onBack && (
        <Button type="button" variant="outline" onClick={onBack}>
          {t('upload.back')}
        </Button>
      )}
      {onNext && (
        <Button type="button" onClick={onNext}>
          {nextLabel ?? t('upload.next')}
        </Button>
      )}
    </div>
  )
}

function FileStep({
  heading,
  busy,
  error,
  chosen,
  onChoose,
  onNext,
}: {
  heading: RefObject<HTMLHeadingElement | null>
  busy: boolean
  error?: string
  chosen?: File
  onChoose: (file: File) => void
  onNext?: () => void
}) {
  const inputId = useId()
  const [problem, setProblem] = useState<string | undefined>(undefined)
  return (
    <Card>
      <CardHeader>
        <StepHeading heading={heading}>{t('upload.file.title')}</StepHeading>
        <CardDescription>{t('upload.file.text', { placeholder: '{{key}}' })}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="space-y-1.5 lg:max-w-xl">
          <Label htmlFor={inputId}>{t('upload.file.choose')}</Label>
          <Input
            id={inputId}
            type="file"
            disabled={busy}
            accept={CONVERTIBLE_EXTENSIONS.map((e) => `.${e}`).join(',')}
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (!file) return
              const extension = extensionOf(file.name) ?? ''
              if (!convertible.has(extension)) {
                setProblem(t('upload.file.notConvertible', { extension }))
                return
              }
              setProblem(undefined)
              onChoose(file)
            }}
          />
          {chosen && !busy && (
            <p className="text-xs text-muted-foreground">
              {t('upload.file.chosen', { name: chosen.name, size: formatFileSize(chosen.size) })}
            </p>
          )}
        </div>
        {busy && <LoadingState label={t('upload.file.analysing')} rows={2} />}
        {(problem ?? error) && (
          <p role="alert" className="text-destructive">
            {problem ?? t('upload.file.failed', { message: error ?? '' })}
          </p>
        )}
        <WordFieldHelp />
        {onNext && <Navigation onNext={onNext} />}
      </CardContent>
    </Card>
  )
}

function FileSummary({ file, analysis }: { file: File; analysis: UploadAnalysis }) {
  return (
    <div className="space-y-1 text-sm" data-testid="upload-file">
      <p className="font-medium break-all">
        {t('upload.file.chosen', { name: file.name, size: formatFileSize(analysis.size) })}
      </p>
      <p className="font-mono text-xs break-all text-muted-foreground">
        {t('upload.file.checksum', { sha: analysis.sha256 })}
      </p>
      {analysis.filledBefore && (
        <p className="text-muted-foreground">{t('upload.file.filledBefore')}</p>
      )}
    </div>
  )
}

function FieldsStep({
  heading,
  analysis,
  values,
  onChange,
  onBack,
  onNext,
}: {
  heading: RefObject<HTMLHeadingElement | null>
  analysis: UploadAnalysis
  values: Record<string, string>
  onChange: (key: string, value: string) => void
  onBack: () => void
  onNext: () => void
}) {
  const fields = analysis.fields ?? []
  const automatic = fields.filter((f) => f.source === 'Automatic')
  const manual = fields.filter((f) => f.source === 'Manual')
  return (
    <Card>
      <CardHeader>
        <StepHeading heading={heading}>{t('upload.fields.title')}</StepHeading>
      </CardHeader>
      <CardContent className="space-y-6 text-sm">
        {fields.length === 0 && <p className="text-muted-foreground">{t('upload.fields.none')}</p>}
        {automatic.length > 0 && (
          <section className="space-y-2" aria-labelledby="upload-automatic">
            <h3 id="upload-automatic" className="font-heading font-semibold">
              {t('upload.fields.automatic')}
            </h3>
            <p className="text-xs text-muted-foreground">{t('upload.fields.automaticText')}</p>
            <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[12rem_minmax(0,1fr)]">
              {automatic.map((f) => (
                <div key={f.key} className="contents">
                  <dt className="text-muted-foreground">{f.label}</dt>
                  <dd>
                    {f.value ? (
                      <WordFieldValue field={f} value={f.value} />
                    ) : (
                      <span className="text-muted-foreground italic">
                        {t('upload.fields.assignedLater')}
                      </span>
                    )}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        {fields.length > 0 && (
          <section className="space-y-3" aria-labelledby="upload-manual">
            <h3 id="upload-manual" className="font-heading font-semibold">
              {t('upload.fields.manual')}
            </h3>
            {manual.length === 0 ? (
              <p className="text-muted-foreground">{t('upload.fields.noManual')}</p>
            ) : (
              <>
                <p className="text-xs text-muted-foreground">{t('upload.fields.manualText')}</p>
                {manual.map((f) => (
                  <ManualField
                    key={f.key}
                    field={f}
                    value={values[f.key] ?? ''}
                    onChange={(value) => {
                      onChange(f.key, value)
                    }}
                  />
                ))}
              </>
            )}
          </section>
        )}
        <Navigation onBack={onBack} onNext={onNext} />
      </CardContent>
    </Card>
  )
}

function ManualField({
  field,
  value,
  onChange,
}: {
  field: WordField
  value: string
  onChange: (value: string) => void
}) {
  const id = useId()
  return (
    <div className="grid gap-1.5 sm:grid-cols-[12rem_minmax(0,1fr)] sm:items-start">
      <Label htmlFor={id} className="flex flex-wrap items-center gap-1.5 sm:pt-1.5">
        {field.label}
        {field.required && (
          <span className="text-destructive" aria-label={t('word.fields.required')}>
            *
          </span>
        )}
        <Badge variant="outline" className="font-normal">
          {t('word.fields.manual')}
        </Badge>
      </Label>
      <div className="min-w-0 space-y-1">
        <WordFieldInput id={id} field={field} value={value} onChange={onChange} />
        {field.warning && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            {t('word.fields.warning', { message: field.warning })}
          </p>
        )}
      </div>
    </div>
  )
}

function PreviewStep({
  heading,
  file,
  values,
  language,
  onBack,
  onNext,
}: {
  heading: RefObject<HTMLHeadingElement | null>
  file: File
  values: Record<string, string>
  language: DocumentLanguage
  onBack: () => void
  onNext: () => void
}) {
  const client = useApiClient()
  const entered = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ''))
  const preview = useQuery({
    queryKey: [
      'uploads',
      'preview',
      file.name,
      file.size,
      file.lastModified,
      JSON.stringify(entered),
      language,
    ],
    queryFn: () => previewUpload(client, file, entered, language),
    retry: false,
    staleTime: Infinity,
  })
  const url = useMemo(
    () =>
      preview.data && typeof URL.createObjectURL === 'function'
        ? URL.createObjectURL(preview.data)
        : undefined,
    [preview.data],
  )
  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url)
    },
    [url],
  )
  return (
    <Card>
      <CardHeader>
        <StepHeading heading={heading}>{t('upload.preview.title')}</StepHeading>
        <CardDescription>{t('upload.preview.text')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {preview.isPending && <LoadingState label={t('upload.preview.loading')} rows={6} />}
        {preview.isError && (
          <div role="alert" className="space-y-2 text-destructive">
            <p>{t('upload.preview.failed', { message: preview.error.message })}</p>
            <Button size="sm" variant="outline" onClick={() => void preview.refetch()}>
              {t('upload.preview.retry')}
            </Button>
          </div>
        )}
        {url && (
          <iframe
            title={t('upload.preview.region', { name: file.name })}
            src={url}
            className="h-[70vh] w-full rounded-lg border"
            data-testid="upload-preview"
          />
        )}
        <Navigation onBack={onBack} onNext={onNext} />
      </CardContent>
    </Card>
  )
}

function PurposeStep({
  heading,
  file,
  analysis,
  values,
  canDocument,
  canTemplate,
  onBack,
  onDocumentCreated,
  onTemplateCreated,
  uploadFile,
  uploadTemplate,
}: {
  heading: RefObject<HTMLHeadingElement | null>
  file: File
  analysis: UploadAnalysis
  values: Record<string, string>
  canDocument: boolean
  canTemplate: boolean
  onBack: () => void
  onDocumentCreated?: (documentId: string) => void
  onTemplateCreated?: (templateId: string) => void
  uploadFile?: (documentId: string) => UploadFile<DocumentVersionView>
  uploadTemplate?: (templateId: string, version: number) => UploadFile<WordTemplate>
}) {
  const templateReason = !canTemplate
    ? t('upload.purpose.noTemplateRole')
    : !analysis.word
      ? t('upload.purpose.notWord')
      : undefined
  const documentReason = canDocument ? undefined : t('upload.purpose.noDocumentRole')
  const [purpose, setPurpose] = useState<Purpose | undefined>(
    documentReason === undefined
      ? 'document'
      : templateReason === undefined
        ? 'template'
        : undefined,
  )
  const ids = { document: useId(), template: useId() }

  const option = (value: Purpose, label: string, text: string, reason: string | undefined) => (
    <label
      className={cn(
        'flex items-start gap-2 rounded-md border p-3',
        reason !== undefined && 'opacity-70',
        purpose === value && 'border-primary',
      )}
    >
      <input
        type="radio"
        name="upload-purpose"
        className="mt-1"
        checked={purpose === value}
        disabled={reason !== undefined}
        aria-describedby={ids[value]}
        onChange={() => {
          setPurpose(value)
        }}
      />
      <span className="space-y-0.5">
        <span className="block font-medium">{label}</span>
        <span id={ids[value]} className="block text-xs text-muted-foreground">
          {text}
          {reason !== undefined && (
            <span className="mt-1 block text-amber-700 dark:text-amber-400">{reason}</span>
          )}
        </span>
      </span>
    </label>
  )

  return (
    <Card>
      <CardHeader>
        <StepHeading heading={heading}>{t('upload.purpose.title')}</StepHeading>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <fieldset className="grid gap-2 lg:max-w-3xl lg:grid-cols-2">
          <legend className="mb-2 font-medium">{t('upload.purpose.legend')}</legend>
          {option(
            'document',
            t('upload.purpose.document'),
            t('upload.purpose.documentText'),
            documentReason,
          )}
          {option(
            'template',
            t('upload.purpose.template'),
            t('upload.purpose.templateText'),
            templateReason,
          )}
        </fieldset>
        {purpose === 'document' && (
          <AsDocument
            file={file}
            analysis={analysis}
            values={values}
            onCreated={onDocumentCreated}
            uploadFile={uploadFile}
          />
        )}
        {purpose === 'template' && (
          <AsTemplate file={file} onCreated={onTemplateCreated} uploadTemplate={uploadTemplate} />
        )}
        <Navigation onBack={onBack} />
      </CardContent>
    </Card>
  )
}

/** As document: choose the class, then title and metadata in the usual form; saving stores the file. */
function AsDocument({
  file,
  analysis,
  values,
  onCreated,
  uploadFile,
}: {
  file: File
  analysis: UploadAnalysis
  values: Record<string, string>
  onCreated?: (documentId: string) => void
  uploadFile?: (documentId: string) => UploadFile<DocumentVersionView>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const classId = useId()
  const classes = useQuery(uploadClassesQuery(client))
  const [chosen, setChosen] = useState('')
  const [language, setLanguage] = useState<DocumentLanguage>(getLocale() === 'de' ? 'de' : 'en')
  const [template, setTemplate] = useState<UploadTemplate | undefined>(undefined)
  const prepare = useMutation({
    mutationFn: () => uploadTemplateOf(client, chosen),
    onSuccess: setTemplate,
  })
  const hasManual = (analysis.fields ?? []).some((f) => f.source === 'Manual')
  // The title shown in the fields step (from the file's title field, else its name) pre-fills the form (DMS-131).
  const title =
    (analysis.fields ?? []).find((f) => f.key === 'title' && f.source === 'Automatic')?.value ||
    baseName(file.name)

  /** Stores the file and the entered values in the new draft; never throws (the document exists). */
  const store = async (documentId: string) => {
    try {
      await (uploadFile ?? uploadOriginal)(documentId)(file, () => undefined)
      if (hasManual) await saveWordValues(client, documentId, values)
      toast(t('upload.document.created'))
    } catch (e) {
      toast(t('upload.document.fileFailed', { message: message(e) }), 'error')
    }
    await queryClient.invalidateQueries({ queryKey: ['documents'] })
  }

  if (template) {
    return (
      <div className="space-y-3" data-testid="upload-document">
        <p className="text-muted-foreground">{t('upload.document.metadata')}</p>
        <DocumentEditor
          templateId={template.templateId}
          version={template.version}
          language={language}
          kind="file"
          initialValues={{ title }}
          afterCreate={store}
          onCreated={onCreated}
          onSubmitted={onCreated}
          aside={
            <Card>
              <CardContent className="pt-6 text-sm text-muted-foreground">
                {t('upload.document.aside')}
              </CardContent>
            </Card>
          }
        />
      </div>
    )
  }

  return (
    <form
      className="grid gap-3 lg:max-w-2xl"
      onSubmit={(event) => {
        event.preventDefault()
        if (chosen) prepare.mutate()
      }}
    >
      <div className="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="space-y-1.5">
          <Label htmlFor={classId}>{t('upload.document.class')}</Label>
          <select
            id={classId}
            required
            className={selectClass}
            value={chosen}
            disabled={!classes.data}
            onChange={(event) => {
              setChosen(event.target.value)
            }}
          >
            <option value="">
              {classes.isPending
                ? t('upload.document.loadingClasses')
                : t('upload.document.chooseClass')}
            </option>
            {(classes.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {(c.path ?? [c.label]).join(' › ')}
              </option>
            ))}
          </select>
          {classes.data?.length === 0 && (
            <p className="text-xs text-muted-foreground">{t('upload.document.noClasses')}</p>
          )}
          {classes.error && (
            <p role="alert" className="text-destructive">
              {t('upload.document.prepareFailed', { message: message(classes.error) })}
            </p>
          )}
        </div>
        <LanguageSelect value={language} onChange={setLanguage} />
      </div>
      <div>
        <Button type="submit" disabled={!chosen || prepare.isPending}>
          {prepare.isPending ? t('upload.document.preparing') : t('upload.document.prepare')}
        </Button>
      </div>
      {prepare.error && (
        <p role="alert" className="text-destructive">
          {t('upload.document.prepareFailed', { message: prepare.error.message })}
        </p>
      )}
    </form>
  )
}

/** As template: name and class; the file becomes the Word file of a new template draft. */
function AsTemplate({
  file,
  onCreated,
  uploadTemplate,
}: {
  file: File
  onCreated?: (templateId: string) => void
  uploadTemplate?: (templateId: string, version: number) => UploadFile<WordTemplate>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const nameId = useId()
  const descriptionId = useId()
  const classId = useId()
  const [name, setName] = useState(baseName(file.name))
  const [description, setDescription] = useState('')
  const [source, setSource] = useState('')
  const classes = useQuery(templateClassesQuery(client))
  const chosen = classes.data?.find((c) => c.id === source)
  const create = useMutation({
    mutationFn: async () => {
      if (!chosen) throw new Error(t('upload.template.chooseClass'))
      const created = await createTemplate(client, {
        name: name.trim(),
        description: description.trim() || undefined,
        documentClassId: chosen.id,
        documentClassVersion: chosen.version,
        content: { sections: [] },
      })
      await (uploadTemplate ?? uploadTemplateWord)(created.templateId, created.version)(
        file,
        () => undefined,
      )
      return created
    },
    onSuccess: async (created) => {
      toast(t('upload.template.created'))
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
      await queryClient.invalidateQueries({ queryKey: ['word-templates'] })
      onCreated?.(created.templateId)
    },
  })
  return (
    <form
      className="grid gap-3 lg:max-w-2xl"
      data-testid="upload-template"
      onSubmit={(event) => {
        event.preventDefault()
        create.mutate()
      }}
    >
      <div className="space-y-1.5">
        <Label htmlFor={nameId}>{t('upload.template.name')}</Label>
        <Input
          id={nameId}
          required
          value={name}
          onChange={(event) => {
            setName(event.target.value)
          }}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={descriptionId}>{t('upload.template.description')}</Label>
        <Input
          id={descriptionId}
          value={description}
          onChange={(event) => {
            setDescription(event.target.value)
          }}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={classId}>{t('upload.template.class')}</Label>
        <select
          id={classId}
          required
          className={selectClass}
          value={source}
          onChange={(event) => {
            setSource(event.target.value)
          }}
        >
          <option value="">{t('upload.template.chooseClass')}</option>
          {(classes.data ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {(c.path ?? [c.label]).join(' › ')}
            </option>
          ))}
        </select>
      </div>
      <div>
        <Button type="submit" disabled={create.isPending || !chosen || name.trim() === ''}>
          {create.isPending ? t('upload.template.creating') : t('upload.template.create')}
        </Button>
      </div>
      {create.error && (
        <p role="alert" className="text-destructive">
          {t('upload.template.failed', { message: create.error.message })}
        </p>
      )}
    </form>
  )
}
