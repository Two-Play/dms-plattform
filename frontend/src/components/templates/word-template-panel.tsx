import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Download, Upload } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useApiClient } from '@/api/context'
import { createTemplate, templateClassesQuery, type TemplateVersion } from '@/api/template-admin'
import {
  downloadTemplateWord,
  isWordFile,
  saveWordFieldDefinitions,
  uploadTemplateWord,
  WORD_FIELD_SOURCES,
  WORD_FIELD_TYPES,
  type UploadFile,
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
                    <tr key={f.key} className="border-b last:border-0">
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
                        {editable ? (
                          <select
                            aria-label={`${t('word.templates.type')}: ${f.key}`}
                            className={selectClass}
                            value={f.type}
                            onChange={(event) => {
                              update(f.key, { type: event.target.value })
                            }}
                          >
                            {WORD_FIELD_TYPES.map((type) => (
                              <option key={type} value={type}>
                                {t(`word.templates.types.${type}`)}
                              </option>
                            ))}
                          </select>
                        ) : (
                          t(`word.templates.types.${f.type as (typeof WORD_FIELD_TYPES)[number]}`)
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
