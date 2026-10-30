import { toast } from '@/lib/toast'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type ReactNode } from 'react'
import { useApiClient } from '@/api/context'
import { createWordDocument } from '@/api/word'
import {
  applyReference,
  createDocument,
  removeReference,
  submitDocument,
  updateDraft,
} from '@/api/documents'
import { ApiRequestError, type FieldIssue } from '@/api/errors'
import {
  partnerAttribute,
  type DocumentVersionView,
  type Partner,
  type ReferenceField,
} from '@/api/partners'
import {
  previewQuery,
  type DocumentLanguage,
  type MetadataValue,
  type TemplateField,
} from '@/api/templates'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { CONFIDENTIALITY_FIELD, isRestrictedInput, withoutHiddenGroups } from './access-groups'
import { DocumentForm } from './document-form'
import { DocumentPreview } from './document-preview'
import { inputsFromDraft, partnerFromDraft } from './draft-values'
import {
  collectValues,
  fieldControlId,
  initialInputs,
  type FieldInput,
  type FormInputs,
} from './field-values'
import { PartnerPicker } from './partner-picker'

/** Delay after the last change before the preview is requested. */
export const PREVIEW_DEBOUNCE_MS = 300

export const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

/** Choice of the language the preview formats values in. */
export function LanguageSelect({
  value,
  onChange,
}: {
  value: DocumentLanguage
  onChange: (language: DocumentLanguage) => void
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor="language">{t('docs.workspace.documentLanguage')}</Label>
      <select
        id="language"
        className={selectClass}
        value={value}
        onChange={(e) => {
          onChange(e.target.value === 'de' ? 'de' : 'en')
        }}
      >
        <option value="en">{t('docs.workspace.english')}</option>
        <option value="de">{t('docs.workspace.german')}</option>
      </select>
    </div>
  )
}

function focusField(key: string) {
  document.getElementById(fieldControlId(key))?.focus()
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
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
  | { kind: 'submitting' }
  | { kind: 'saved'; label: string; warning?: string }
  | { kind: 'failed'; message: string; missing?: FieldIssue[] }

/** A backend issue for a field, shown while the field still has the value that was sent. */
interface Rejected {
  message: string
  sent: string | undefined
}

/** A saved draft to continue: its document id and open version. */
export interface SavedDraft {
  documentId: string
  version: DocumentVersionView
}

export interface DocumentWorkspaceProps {
  templateId: string
  version: number
  language: DocumentLanguage
  fields: TemplateField[]
  references: ReferenceField[]
  /** The draft to continue; without it, the first save creates the document. */
  draft?: SavedDraft
  /** Called after the draft was submitted for review. */
  onSubmitted?: (documentId: string, version: DocumentVersionView) => void
  /**
   * What the content is (DMS-115): `structured` (default) with the live preview; `file` (an uploaded file) or
   * `word` (a copy of the Word template's file) without preview: `aside` is shown instead.
   */
  kind?: 'structured' | 'file' | 'word'
  /** Shown next to the form instead of the live preview of structured content. */
  aside?: ReactNode
  /** Called once the first save created the document (file and Word documents continue on its page). */
  onCreated?: (documentId: string) => void
  /**
   * Awaited after the first save created the document, before `onCreated` and before a submission
   * continues (DMS-127: the upload wizard stores the file and its values). Must not throw.
   */
  afterCreate?: (documentId: string) => Promise<void>
}

function conflictMessage(e: ApiRequestError, action: 'saved' | 'submitted'): string {
  return action === 'saved'
    ? t('docs.workspace.conflictSaved', { message: e.message })
    : t('docs.workspace.conflictSubmitted', { message: e.message })
}

/**
 * Form and live preview of a document draft (US-03.2): fields generated from the template version,
 * partner take-over (US-03.1), debounced preview, "Save draft" and "Submit for review". Used by
 * "New document" and to continue a saved draft.
 */
export function DocumentWorkspace({
  templateId,
  version,
  language,
  fields,
  references,
  draft,
  onSubmitted,
  kind = 'structured',
  aside,
  onCreated,
  afterCreate,
}: DocumentWorkspaceProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const [inputs, setInputs] = useState<FormInputs>(() =>
    draft ? inputsFromDraft(fields, draft.version.values) : initialInputs(fields),
  )
  const [picked, setPicked] = useState<Record<string, Partner | undefined>>(() =>
    draft
      ? Object.fromEntries(
          references.map((r) => [r.key, partnerFromDraft(r, draft.version.values)]),
        )
      : {},
  )
  const [saved, setSaved] = useState<Saved | undefined>(() => {
    if (!draft) return undefined
    const applied: Record<string, string> = {}
    for (const reference of references) {
      const partner = partnerFromDraft(reference, draft.version.values)
      if (partner) applied[reference.key] = partner.id
    }
    return { documentId: draft.documentId, label: draft.version.version, applied }
  })
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' })
  const [rejected, setRejected] = useState<Record<string, Rejected>>({})
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
  const { values, errors } = useMemo(() => {
    // Groups of a document that is no longer confidential are hidden and not sent (DMS-101).
    const restricted = isRestrictedInput(formInputs[CONFIDENTIALITY_FIELD])
    const collected = collectValues(fields, formInputs)
    return {
      values: withoutHiddenGroups(collected.values, restricted),
      errors: withoutHiddenGroups(collected.errors, restricted),
    }
  }, [fields, formInputs])
  const sent = useDebouncedValue(values, PREVIEW_DEBOUNCE_MS)
  const preview = useQuery({
    ...previewQuery(client, templateId, version, sent, language),
    placeholderData: keepPreviousData,
    enabled: kind === 'structured',
  })

  // Backend issues apply to the values that were sent; show them while the field still has that value.
  const serverErrors: Record<string, string> = {}
  for (const issue of preview.data?.issues ?? []) {
    const key = issue.field
    if (key !== undefined && JSON.stringify(sent[key]) === JSON.stringify(values[key])) {
      serverErrors[key] = issue.message
    }
  }
  for (const [key, issue] of Object.entries(rejected)) {
    if (JSON.stringify(values[key]) === issue.sent) serverErrors[key] = issue.message
  }

  const reject = (issues: FieldIssue[], at: Record<string, MetadataValue>) => {
    setRejected(
      Object.fromEntries(
        issues.map((i) => [i.field, { message: i.message, sent: JSON.stringify(at[i.field]) }]),
      ),
    )
  }

  /** Saves the draft; the document id if it was saved (taking partners over may still have failed). */
  const save = async (submitting = false): Promise<string | undefined> => {
    const title = values.title?.type === 'Text' ? values.title.value : ''
    if (title === '') {
      setSaveState({ kind: 'failed', message: t('docs.workspace.titleMissing') })
      return undefined
    }
    // Values taken over from the partner master are set by the server, not sent as entered values.
    const filled = new Set(fields.filter((f) => f.reference).map((f) => f.key))
    const entered = Object.fromEntries(Object.entries(values).filter(([key]) => !filled.has(key)))
    setSaveState({ kind: 'saving' })
    setRejected({})
    let current = saved
    try {
      if (current) {
        const updated = await updateDraft(client, current.documentId, { title, values: entered })
        current = { ...current, label: updated.version }
      } else {
        const body = { templateId, templateVersion: version, title, values: entered }
        const created =
          kind === 'word'
            ? await createWordDocument(client, body)
            : await createDocument(client, kind === 'file' ? { ...body, kind: 'File' } : body)
        current = { documentId: created.id, label: created.current.version, applied: {} }
        setSaved(current)
        if (afterCreate) await afterCreate(created.id)
        if (kind !== 'structured') onCreated?.(created.id)
      }
      setSaved(current)
    } catch (e) {
      if (e instanceof ApiRequestError && e.issues.length > 0) {
        reject(e.issues, values)
        setSaveState({
          kind: 'failed',
          message: t('docs.workspace.correctAndSave', { message: e.message }),
        })
      } else if (e instanceof ApiRequestError && e.status === 409) {
        setSaveState({ kind: 'failed', message: conflictMessage(e, 'saved') })
      } else {
        setSaveState({ kind: 'failed', message: message(e) })
      }
      return undefined
    }
    // The draft is saved; taking the partners over may still fail while the partner master is down.
    const warnings: string[] = []
    for (const reference of references) {
      const partner = picked[reference.key]
      const applied = current.applied[reference.key]
      if (partner?.id === applied) continue
      try {
        const updated: DocumentVersionView = partner
          ? await applyReference(client, current.documentId, reference.key, partner.id)
          : await removeReference(client, current.documentId, reference.key)
        const others = Object.entries(current.applied).filter(([key]) => key !== reference.key)
        const nextApplied: Record<string, string> = Object.fromEntries(
          partner ? [...others, [reference.key, partner.id]] : others,
        )
        current = { ...current, label: updated.version, applied: nextApplied }
        setSaved(current)
      } catch (e) {
        warnings.push(
          t('docs.workspace.notTakenOver', { label: reference.label, message: message(e) }),
        )
      }
    }
    setSaveState({
      kind: 'saved',
      label: current.label,
      warning: warnings.length > 0 ? warnings.join(' ') : undefined,
    })
    // The list and the document page show the new title and version.
    void queryClient.invalidateQueries({ queryKey: ['documents'] })
    if (!submitting) toast(t('docs.workspace.draftSaved'))
    return current.documentId
  }

  const submit = async () => {
    const documentId = await save(true)
    if (!documentId) return
    setSaveState({ kind: 'submitting' })
    try {
      const submitted = await submitDocument(client, documentId)
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      setSaveState({ kind: 'saved', label: submitted.version })
      toast(t('docs.workspace.submitted'))
      onSubmitted?.(documentId, submitted)
    } catch (e) {
      if (e instanceof ApiRequestError && e.code === 'incomplete') {
        reject(e.issues, values)
        setSaveState({
          kind: 'failed',
          message: t('docs.workspace.incomplete'),
          missing: e.issues,
        })
      } else if (e instanceof ApiRequestError && e.issues.length > 0) {
        reject(e.issues, values)
        setSaveState({
          kind: 'failed',
          message: t('docs.workspace.correct', { message: e.message }),
        })
      } else if (e instanceof ApiRequestError && e.code === 'invalid') {
        setSaveState({
          kind: 'failed',
          message: t('docs.workspace.invalid', { message: e.message }),
        })
      } else if (e instanceof ApiRequestError && e.status === 409) {
        setSaveState({ kind: 'failed', message: conflictMessage(e, 'submitted') })
      } else {
        setSaveState({
          kind: 'failed',
          message: t('docs.workspace.submitFailed', { message: message(e) }),
        })
      }
    }
  }

  const busy = saveState.kind === 'saving' || saveState.kind === 'submitting'

  return (
    // Stacked on narrow screens; side by side from lg, the preview wider than the form. There the preview
    // stays in view and scrolls on its own, so a twelve-page document does not push the form away.
    <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <div className="min-w-0 space-y-4">
        {references.length > 0 && (
          <fieldset className="space-y-3">
            <legend className="mb-2 font-heading text-sm font-semibold">
              {t('docs.workspace.fromPartnerMaster')}
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
            disabled={busy}
            aria-describedby="save-draft-note"
            onClick={() => {
              void save()
            }}
          >
            {saveState.kind === 'saving'
              ? t('docs.workspace.saving')
              : t('docs.workspace.saveDraft')}
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={busy}
            aria-describedby="save-draft-note"
            onClick={() => {
              void submit()
            }}
          >
            {saveState.kind === 'submitting'
              ? t('docs.workspace.submitting')
              : t('docs.workspace.submit')}
          </Button>
          <div id="save-draft-note" className="space-y-1 text-xs" aria-live="polite">
            {saveState.kind === 'idle' && (
              <p className="text-muted-foreground">{t('docs.workspace.saveHint')}</p>
            )}
            {saveState.kind === 'saved' && (
              <p>{t('docs.workspace.savedAs', { label: saveState.label })}</p>
            )}
            {saveState.kind === 'saved' && saveState.warning && (
              <p role="alert" className="text-destructive">
                {saveState.warning}
              </p>
            )}
            {saveState.kind === 'failed' && (
              <div role="alert" className="text-destructive">
                <p>{saveState.message}</p>
                {saveState.missing && saveState.missing.length > 0 && (
                  <ul className="mt-1 list-disc pl-4">
                    {saveState.missing.map((issue) => (
                      <li key={issue.field}>
                        <button
                          type="button"
                          className="underline underline-offset-2"
                          onClick={() => {
                            focusField(issue.field)
                          }}
                        >
                          {issue.label}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
      <div className="min-w-0 lg:sticky lg:top-20 lg:max-h-[calc(100svh-6rem)] lg:self-start lg:overflow-y-auto">
        {kind === 'structured' ? (
          <DocumentPreview
            preview={preview.data}
            updating={preview.isFetching}
            error={preview.error}
            onFocusField={focusField}
          />
        ) : (
          aside
        )}
      </div>
    </div>
  )
}
