import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Download, Upload } from 'lucide-react'
import { Fragment, useId, useRef, useState } from 'react'
import { useApiClient } from '@/api/context'
import { createTemplate, templateClassesQuery, type TemplateVersion } from '@/api/template-admin'
import {
  downloadTemplateWord,
  isWordFile,
  saveWordFieldDefinitions,
  uploadTemplateWord,
  WORD_FIELD_SOURCES,
  WORD_FIELD_TYPES,
  WORD_SIMPLE_TYPES,
  type UploadFile,
  type WordColumnDefinition,
  type WordFieldDefinition,
  type WordTemplate,
} from '@/api/word'
import { WordFieldHelp } from '@/components/documents/word-field-help'
import { selectClass } from '@/components/documents/document-workspace'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { formatDateTime, formatFileSize } from '@/i18n/format'
import { toast } from '@/lib/toast'

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/**
 * The Word file of a Word template version (DMS-115): download, replace while the version is a draft, and
 * the definitions of its fields (label, type, required, automatic or manual).
 */
export function WordTemplatePanel({
  version,
  word,
  upload,
}: {
  version: TemplateVersion
  word: WordTemplate
  /** Replaceable in tests. */
  upload?: UploadFile<WordTemplate>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const editable = version.status === 'Draft'
  const fileInput = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<number | undefined>(undefined)
  const [problem, setProblem] = useState<string | undefined>(undefined)
  const [fields, setFields] = useState<WordFieldDefinition[]>(word.fields ?? [])
  const send = upload ?? uploadTemplateWord(version.templateId, version.version)

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['word-templates'] })
  const download = useMutation({ mutationFn: () => downloadTemplateWord(client, word) })
  const save = useMutation({
    mutationFn: () => saveWordFieldDefinitions(client, version.templateId, version.version, fields),
    onSuccess: async (saved) => {
      setFields(saved.fields ?? [])
      toast(t('word.templates.fieldsSaved'))
      await refresh()
    },
  })

  const replace = async (file: File) => {
    if (!isWordFile(file.name)) {
      setProblem(t('word.templates.notDocx'))
      return
    }
    setProblem(undefined)
    setProgress(0)
    try {
      const saved = await send(file, (sent, total) => {
        setProgress(total > 0 ? Math.round((sent / total) * 100) : 0)
      })
      setFields(saved.fields ?? [])
      toast(t('word.file.uploaded'))
      await refresh()
    } catch (e) {
      setProblem(t('word.file.uploadFailed', { message: message(e) }))
    } finally {
      setProgress(undefined)
    }
  }

  const update = (key: string, change: Partial<WordFieldDefinition>) => {
    setFields((current) => current.map((f) => (f.key === key ? { ...f, ...change } : f)))
  }

  return (
    <div className="space-y-6" data-testid="word-template">
      <Card>
        <CardHeader>
          <CardTitle>{t('word.templates.panelTitle')}</CardTitle>
          <CardDescription>
            {editable ? t('word.templates.panelText') : t('word.templates.panelReadOnly')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="font-medium break-all">{word.name}</p>
          <p className="text-muted-foreground">
            {formatFileSize(word.size)} ·{' '}
            {t('word.file.uploadedBy', { date: formatDateTime(word.uploadedAt) })}{' '}
            <UserName id={word.uploadedBy} />
          </p>
          <p className="font-mono text-xs break-all text-muted-foreground">
            {t('word.file.checksum', { sha: word.sha256 })}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={download.isPending}
              onClick={() => {
                download.mutate()
              }}
            >
              <Download className="size-4" aria-hidden />
              {t('word.templates.download')}
            </Button>
            {editable && (
              <>
                <input
                  ref={fileInput}
                  type="file"
                  className="sr-only"
                  aria-label={t('word.templates.upload')}
                  accept=".docx"
                  onChange={(event) => {
                    const chosen = event.target.files?.[0]
                    event.target.value = ''
                    if (chosen) void replace(chosen)
                  }}
                />
                <Button
                  size="sm"
                  disabled={progress !== undefined}
                  onClick={() => fileInput.current?.click()}
                >
                  <Upload className="size-4" aria-hidden />
                  {progress !== undefined
                    ? t('word.file.uploading', { percent: progress })
                    : t('word.templates.upload')}
                </Button>
              </>
            )}
          </div>
          {problem && (
            <p role="alert" className="text-destructive">
              {problem}
            </p>
          )}
          {download.error && (
            <p role="alert" className="text-destructive">
              {t('word.file.downloadFailed', { message: download.error.message })}
            </p>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>{t('word.templates.fieldsTitle')}</CardTitle>
          <CardDescription>{t('word.templates.fieldsText')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {fields.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('word.fields.none')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">{t('word.templates.fieldsTitle')}</caption>
                <thead>
                  <tr className="border-b text-left">
                    <th scope="col" className="p-2">
                      {t('word.templates.key')}
                    </th>
                    <th scope="col" className="p-2">
                      {t('word.templates.label')}
                    </th>
                    <th scope="col" className="p-2">
                      {t('word.templates.type')}
                    </th>
                    <th scope="col" className="p-2">
                      {t('word.templates.requiredColumn')}
                    </th>
                    <th scope="col" className="p-2">
                      {t('word.templates.source')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {fields.map((f) => (
                    <Fragment key={f.key}>
                      <tr className={details(f) ? '' : 'border-b last:border-0'}>
                        <td className="p-2 font-mono text-xs">{f.key}</td>
                        <td className="p-2">
                          {editable ? (
                            <Input
                              aria-label={`${t('word.templates.label')}: ${f.key}`}
                              value={f.label}
                              onChange={(event) => {
                                update(f.key, { label: event.target.value })
                              }}
                            />
                          ) : (
                            f.label
                          )}
                        </td>
                        <td className="p-2">
                          {editable && f.type !== 'Rows' ? (
                            <TypeSelect
                              label={`${t('word.templates.type')}: ${f.key}`}
                              value={f.type}
                              onChange={(type) => {
                                update(f.key, { type })
                              }}
                            />
                          ) : (
                            typeLabel(f.type)
                          )}
                        </td>
                        <td className="p-2">
                          <input
                            type="checkbox"
                            className="size-4"
                            aria-label={`${t('word.templates.requiredColumn')}: ${f.key}`}
                            checked={f.required}
                            disabled={!editable}
                            onChange={(event) => {
                              update(f.key, { required: event.target.checked })
                            }}
                          />
                        </td>
                        <td className="p-2">
                          {editable ? (
                            <select
                              aria-label={`${t('word.templates.source')}: ${f.key}`}
                              className={selectClass}
                              value={f.source}
                              onChange={(event) => {
                                update(f.key, { source: event.target.value })
                              }}
                            >
                              {WORD_FIELD_SOURCES.map((source) => (
                                <option key={source} value={source}>
                                  {t(`word.templates.sources.${source}`)}
                                </option>
                              ))}
                            </select>
                          ) : (
                            t(
                              `word.templates.sources.${f.source as (typeof WORD_FIELD_SOURCES)[number]}`,
                            )
                          )}
                        </td>
                      </tr>
                      {details(f) && (
                        <tr className="border-b last:border-0">
                          <td />
                          <td colSpan={4} className="space-y-2 p-2 pt-0">
                            <FieldDetails
                              field={f}
                              editable={editable}
                              onChange={(change) => {
                                update(f.key, change)
                              }}
                            />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {editable && fields.length > 0 && (
            <Button
              size="sm"
              disabled={save.isPending}
              onClick={() => {
                save.mutate()
              }}
            >
              {t('word.templates.saveFields')}
            </Button>
          )}
          {save.error && (
            <p role="alert" className="text-sm text-destructive">
              {t('word.templates.fieldsFailed', { message: save.error.message })}
            </p>
          )}
          <WordFieldHelp />
        </CardContent>
      </Card>
    </div>
  )
}

/** The label of a field type; unknown types as they are. */
function typeLabel(type: string): string {
  return (WORD_FIELD_TYPES as readonly string[]).includes(type)
    ? t(`word.templates.types.${type as (typeof WORD_FIELD_TYPES)[number]}`)
    : type
}

/** The types a designer can choose (not Rows, which only repeating sections of the Word file have). */
function TypeSelect({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (type: string) => void
}) {
  return (
    <select
      aria-label={label}
      className={selectClass}
      value={value}
      onChange={(event) => {
        onChange(event.target.value)
      }}
    >
      {WORD_SIMPLE_TYPES.map((type) => (
        <option key={type} value={type}>
          {t(`word.templates.types.${type}`)}
        </option>
      ))}
    </select>
  )
}

/** Whether a field has details below its row: options, columns or a warning of the scan (DMS-122). */
function details(f: WordFieldDefinition): boolean {
  return f.type === 'Choice' || f.type === 'Rows' || Boolean(f.warning)
}

/** The options of a Choice field, the columns of a Rows field and the warning of the scan (DMS-122). */
function FieldDetails({
  field,
  editable,
  onChange,
}: {
  field: WordFieldDefinition
  editable: boolean
  onChange: (change: Partial<WordFieldDefinition>) => void
}) {
  const columns = field.columns ?? []
  const updateColumn = (key: string, change: Partial<WordColumnDefinition>) => {
    onChange({ columns: columns.map((c) => (c.key === key ? { ...c, ...change } : c)) })
  }
  return (
    <>
      {field.warning && (
        <p className="text-xs text-amber-700 dark:text-amber-400">
          {t('word.templates.warning', { message: field.warning })}
        </p>
      )}
      {field.type === 'Choice' && (
        <OptionsInput
          label={t('word.templates.options', { key: field.key })}
          options={field.options ?? []}
          editable={editable}
          onChange={(options) => {
            onChange({ options })
          }}
        />
      )}
      {field.type === 'Rows' && (
        <div className="space-y-1">
          <p className="text-xs font-medium">{t('word.templates.columns')}</p>
          <table className="w-full text-xs">
            <caption className="sr-only">
              {t('word.templates.columns')}: {field.key}
            </caption>
            <tbody>
              {columns.map((c) => {
                const name = t('word.templates.columnOf', { key: field.key, column: c.key })
                return (
                  <tr key={c.key} className="align-top">
                    <td className="p-1 font-mono">{c.key}</td>
                    <td className="p-1">
                      {editable ? (
                        <Input
                          aria-label={`${t('word.templates.label')}: ${name}`}
                          value={c.label}
                          onChange={(event) => {
                            updateColumn(c.key, { label: event.target.value })
                          }}
                        />
                      ) : (
                        c.label
                      )}
                    </td>
                    <td className="space-y-1 p-1">
                      {editable ? (
                        <TypeSelect
                          label={`${t('word.templates.type')}: ${name}`}
                          value={c.type}
                          onChange={(type) => {
                            updateColumn(c.key, { type })
                          }}
                        />
                      ) : (
                        typeLabel(c.type)
                      )}
                      {c.type === 'Choice' && (
                        <OptionsInput
                          label={t('word.templates.options', { key: name })}
                          options={c.options ?? []}
                          editable={editable}
                          onChange={(options) => {
                            updateColumn(c.key, { options })
                          }}
                        />
                      )}
                    </td>
                    <td className="p-1">
                      <input
                        type="checkbox"
                        className="size-4"
                        aria-label={`${t('word.templates.requiredColumn')}: ${name}`}
                        checked={c.required}
                        disabled={!editable}
                        onChange={(event) => {
                          updateColumn(c.key, { required: event.target.checked })
                        }}
                      />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** Options one per line; empty lines are dropped by the backend. */
function OptionsInput({
  label,
  options,
  editable,
  onChange,
}: {
  label: string
  options: string[]
  editable: boolean
  onChange: (options: string[]) => void
}) {
  const id = useId()
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-xs">
        {t('word.templates.optionsLabel')}
      </Label>
      <textarea
        id={id}
        aria-label={label}
        className="min-h-16 w-full rounded-md border bg-transparent px-2 py-1 text-sm"
        value={options.join('\n')}
        readOnly={!editable}
        onChange={(event) => {
          onChange(event.target.value.split('\n'))
        }}
      />
    </div>
  )
}

/**
 * "New Word template" (DMS-115): name, the document class (from the read-only class list for designers,
 * DMS-121) and the DOCX; creates the template for the class's latest version and uploads the file.
 */
export function NewWordTemplateCard({
  upload,
}: {
  /** Replaceable in tests. */
  upload?: (templateId: string, version: number) => UploadFile<WordTemplate>
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const nameId = useId()
  const descriptionId = useId()
  const classId = useId()
  const fileId = useId()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [source, setSource] = useState('')
  const [file, setFile] = useState<File | undefined>(undefined)
  const [problem, setProblem] = useState<string | undefined>(undefined)
  const classes = useQuery(templateClassesQuery(client))
  const chosen = classes.data?.find((c) => c.id === source)

  const create = useMutation({
    mutationFn: async () => {
      if (!chosen || !file) throw new Error(t('word.templates.notDocx'))
      const created = await createTemplate(client, {
        name: name.trim(),
        description: description.trim() || undefined,
        documentClassId: chosen.id,
        documentClassVersion: chosen.version,
        content: { sections: [] },
      })
      const send = (upload ?? uploadTemplateWord)(created.templateId, created.version)
      await send(file, () => undefined)
      return created
    },
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['template-admin'] })
      await queryClient.invalidateQueries({ queryKey: ['word-templates'] })
      void navigate({ to: '/templates/$templateId', params: { templateId: created.templateId } })
    },
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('word.templates.newTitle')}</CardTitle>
        <CardDescription>{t('word.templates.newText')}</CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="grid gap-3 lg:max-w-2xl"
          onSubmit={(event) => {
            event.preventDefault()
            if (!file || !isWordFile(file.name)) {
              setProblem(t('word.templates.notDocx'))
              return
            }
            setProblem(undefined)
            create.mutate()
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor={nameId}>{t('word.templates.name')}</Label>
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
            <Label htmlFor={descriptionId}>{t('word.templates.description')}</Label>
            <Input
              id={descriptionId}
              value={description}
              onChange={(event) => {
                setDescription(event.target.value)
              }}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={classId}>{t('word.templates.classOf')}</Label>
            <select
              id={classId}
              required
              className={selectClass}
              value={source}
              onChange={(event) => {
                setSource(event.target.value)
              }}
            >
              <option value="">{t('word.templates.chooseClass')}</option>
              {(classes.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {(c.path ?? [c.label]).join(' › ')}
                </option>
              ))}
            </select>
            {classes.error && (
              <p role="alert" className="text-sm text-destructive">
                {t('word.templates.classesFailed', { message: message(classes.error) })}
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={fileId}>{t('word.templates.file')}</Label>
            <Input
              id={fileId}
              type="file"
              accept=".docx"
              onChange={(event) => {
                setFile(event.target.files?.[0])
              }}
            />
          </div>
          <div>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? t('word.templates.creating') : t('word.templates.create')}
            </Button>
          </div>
          {(problem ?? create.error) && (
            <p role="alert" className="text-sm text-destructive">
              {problem ?? t('word.templates.createFailed', { message: message(create.error) })}
            </p>
          )}
          <WordFieldHelp />
        </form>
      </CardContent>
    </Card>
  )
}
