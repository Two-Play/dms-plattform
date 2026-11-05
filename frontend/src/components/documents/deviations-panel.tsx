import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  applyDeviations,
  deviationsQuery,
  MAX_DEVIATION_REASON,
  requestDeviation,
  withdrawDeviation,
  type Deviation,
  type DeviationRequest,
} from '@/api/deviations'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView } from '@/api/partners'
import { ErrorState } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { changeText, locationText, lockedSectionId } from './deviation-labels'

/** A locked section of the version, or the section a request names, with what the panel shows for it. */
interface LockedSection {
  id: string
  title: string
  request: DeviationRequest | undefined
  /** The report has changes of this section. */
  changed: boolean
  /** The report has changes of this section that no active request covers. */
  unauthorized: boolean
}

/** A sentence with a person in it: the name is a component, so the text is split around it. */
function WithName({ text, id }: { text: string; id: string }) {
  const [before, after] = text.split('{name}')
  return (
    <>
      {before}
      <UserName id={id} />
      {after}
    </>
  )
}

/** A refused action in the caller's language; a conflict means the shown state was out of date. */
function failure(error: Error, action: 'request' | 'withdraw'): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t(`deviations.${action}.forbidden`)
    if (error.status === 409) return t(`deviations.${action}.conflict`, { message: error.message })
  }
  return t(`deviations.${action}.failed`, { message: error.message })
}

function focusSection(sectionId: string) {
  document.getElementById(lockedSectionId(sectionId))?.focus()
}

interface FormProps {
  documentId: string
  section: LockedSection
  onDone: () => void
}

function RequestForm({ documentId, section, onDone }: FormProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const trimmed = reason.trim()
  const missing = trimmed === ''
  const tooLong = trimmed.length > MAX_DEVIATION_REASON

  const request = useMutation({
    mutationFn: () =>
      requestDeviation(client, documentId, { sectionId: section.id, reason: trimmed }),
    onSuccess: async (deviations) => {
      onDone()
      toast(t('deviations.request.done', { section: section.title }))
      await applyDeviations(queryClient, documentId, deviations)
    },
    onError: (error) => {
      // The list behind the dialog was out of date; the reason stays in the form.
      if (error instanceof ApiRequestError && error.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
      }
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (missing || tooLong) return
    request.mutate()
  }

  const reasonId = `${id}-reason`
  const hintId = `${id}-reason-hint`
  const invalid = tooLong || (attempted && missing)
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={reasonId}>{t('deviations.request.reason')}</Label>
        <Textarea
          id={reasonId}
          value={reason}
          rows={4}
          required
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-invalid={invalid ? true : undefined}
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={invalid ? 'alert' : undefined}
          className={invalid ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {tooLong
            ? t('deviations.request.reasonTooLong', {
                max: MAX_DEVIATION_REASON,
                count: trimmed.length,
              })
            : invalid
              ? t('deviations.request.reasonMissing')
              : t('deviations.request.reasonHint')}
        </p>
      </div>
      {request.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(request.error, 'request')}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={request.isPending}>
          {request.isPending ? t('deviations.request.confirming') : t('deviations.request.confirm')}
        </Button>
      </DialogFooter>
    </form>
  )
}

function WithdrawForm({
  documentId,
  section,
  requestId,
  onDone,
}: FormProps & { requestId: string }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const withdraw = useMutation({
    mutationFn: () => withdrawDeviation(client, documentId, requestId),
    onSuccess: async (deviations) => {
      onDone()
      toast(t('deviations.withdraw.done', { section: section.title }))
      await applyDeviations(queryClient, documentId, deviations)
    },
    onError: (error) => {
      if (error instanceof ApiRequestError && error.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
      }
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    withdraw.mutate()
  }

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      {withdraw.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(withdraw.error, 'withdraw')}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="destructive" disabled={withdraw.isPending}>
          {withdraw.isPending
            ? t('deviations.withdraw.confirming')
            : t('deviations.withdraw.confirm')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** The dialog of a section: requesting a deviation, or withdrawing its active request. */
function SectionDialog({
  documentId,
  section,
  onClose,
}: {
  documentId: string
  section: LockedSection
  onClose: () => void
}) {
  const { request } = section
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent
        // The button that opened the dialog may be gone: focus goes back to the section.
        onCloseAutoFocus={(e) => {
          e.preventDefault()
          focusSection(section.id)
        }}
      >
        <DialogHeader>
          <DialogTitle>
            {request
              ? t('deviations.withdraw.title', { section: section.title })
              : t('deviations.request.title', { section: section.title })}
          </DialogTitle>
          <DialogDescription>
            {!request
              ? t('deviations.request.description')
              : section.changed
                ? t('deviations.withdraw.descriptionChanged')
                : t('deviations.withdraw.description')}
          </DialogDescription>
        </DialogHeader>
        {request ? (
          <WithdrawForm
            documentId={documentId}
            section={section}
            requestId={request.id}
            onDone={onClose}
          />
        ) : (
          <RequestForm documentId={documentId} section={section} onDone={onClose} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function SectionState({ section }: { section: LockedSection }) {
  if (section.unauthorized) {
    return <Badge variant="destructive">{t('deviations.changedWithoutRequest')}</Badge>
  }
  if (section.request) return <Badge variant="secondary">{t('deviations.requested')}</Badge>
  return <Badge variant="outline">{t('deviations.standardText')}</Badge>
}

function SectionRow({
  section,
  editable,
  onAct,
}: {
  section: LockedSection
  editable: boolean
  onAct: () => void
}) {
  const { request } = section
  return (
    <li
      id={lockedSectionId(section.id)}
      tabIndex={-1}
      data-testid="locked-section"
      className="space-y-1 rounded-lg border p-3 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <div className="flex flex-wrap items-center gap-2">
        <Lock aria-hidden="true" className="size-4 text-muted-foreground" />
        <span className="sr-only">{t('deviations.locked')}:</span>
        <span className="font-medium">{section.title}</span>
        <SectionState section={section} />
        {editable && (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto"
            aria-label={
              request
                ? t('deviations.withdraw.actionFor', { section: section.title })
                : t('deviations.request.actionFor', { section: section.title })
            }
            onClick={onAct}
          >
            {request ? t('deviations.withdraw.action') : t('deviations.request.action')}
          </Button>
        )}
      </div>
      {request && (
        <>
          <p className="break-words whitespace-pre-wrap">{request.reason}</p>
          <p className="text-xs text-muted-foreground">
            <WithName
              id={request.requestedBy}
              text={t('deviations.requestedBy', {
                name: '{name}',
                date: formatDateTime(request.requestedAt),
              })}
            />
            {request.basedOn && <> {t('deviations.carriedOver')}</>}
          </p>
        </>
      )}
    </li>
  )
}

function WithdrawnRequests({
  requests,
  titleOf,
}: {
  requests: DeviationRequest[]
  titleOf: (sectionId: string) => string
}) {
  if (requests.length === 0) return null
  return (
    <section className="space-y-2">
      <h3 className="text-sm font-medium">{t('deviations.withdrawnHeading')}</h3>
      <ul className="space-y-2 text-sm">
        {requests.map((request) => (
          <li key={request.id} className="rounded-lg border border-dashed p-3">
            <p className="font-medium">{titleOf(request.sectionId)}</p>
            <p className="break-words whitespace-pre-wrap">{request.reason}</p>
            <p className="text-xs text-muted-foreground">
              <WithName
                id={request.requestedBy}
                text={t('deviations.requestedBy', {
                  name: '{name}',
                  date: formatDateTime(request.requestedAt),
                })}
              />
              {request.withdrawnBy && request.withdrawnAt && (
                <>
                  {' · '}
                  <WithName
                    id={request.withdrawnBy}
                    text={t('deviations.withdrawnBy', {
                      name: '{name}',
                      date: formatDateTime(request.withdrawnAt),
                    })}
                  />
                </>
              )}
            </p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function ChangeCell({ deviation }: { deviation: Deviation }) {
  const { label, before, after } = changeText(deviation.change)
  return (
    <>
      <p>{label}</p>
      {before !== undefined && (
        <p className="break-words">
          <span className="text-muted-foreground">{t('deviations.report.before')}</span>{' '}
          <del>{before}</del>
        </p>
      )}
      {after !== undefined && (
        <p className="break-words">
          <span className="text-muted-foreground">{t('deviations.report.after')}</span>{' '}
          <ins className="no-underline">{after}</ins>
        </p>
      )}
    </>
  )
}

function Report({
  deviations,
  label,
  titleOf,
}: {
  deviations: Deviation[]
  label: string
  titleOf: (sectionId: string) => string
}) {
  return (
    <section className="space-y-2">
      <h3 className="text-sm font-medium">{t('deviations.report.heading')}</h3>
      {deviations.length === 0 ? (
        <EmptyState
          title={t('deviations.report.empty')}
          description={t('deviations.report.emptyHint')}
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="deviation-report">
            <caption className="sr-only">{t('deviations.report.caption', { label })}</caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('deviations.report.location')}
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('deviations.report.change')}
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('deviations.report.reason')}
                </th>
                <th scope="col" className="py-1.5 font-medium">
                  {t('deviations.report.authorized')}
                </th>
              </tr>
            </thead>
            <tbody>
              {deviations.map((deviation, index) => (
                <tr
                  // The report has no ids; it is replaced as a whole and its order is stable.
                  key={`${deviation.location}:${String(index)}`}
                  className="border-b align-top last:border-0"
                >
                  <td className="py-1.5 pr-3">
                    {locationText(deviation, titleOf(deviation.section))}
                  </td>
                  <td className="py-1.5 pr-3">
                    <ChangeCell deviation={deviation} />
                  </td>
                  <td className="py-1.5 pr-3 break-words whitespace-pre-wrap">
                    {(deviation.reasons ?? []).join('\n') || (
                      <span className="text-muted-foreground">
                        {t('deviations.report.noReason')}
                      </span>
                    )}
                  </td>
                  <td className="py-1.5">
                    <Badge variant={deviation.authorized ? 'secondary' : 'destructive'}>
                      {deviation.authorized
                        ? t('deviations.report.yes')
                        : t('deviations.report.no')}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/**
 * Locked sections, deviation requests and the deviation report of a version (US-01.1, DMS-84, ADR 0022).
 * On a draft, `editable` adds requesting and withdrawing. Whoever sees a draft may change it (`ReadDocument`
 * and `EditDraft` on a draft ask for the same roles); the backend checks again. From submission on the
 * requests are frozen, and readers of the version see them with the report as it was submitted.
 */
export function DeviationsPanel({
  documentId,
  version,
  editable,
}: {
  documentId: string
  version: DocumentVersionView
  editable: boolean
}) {
  const client = useApiClient()
  const report = useQuery(deviationsQuery(client, documentId, version.id))
  // The section as it was when the dialog opened: a refresh behind the dialog must not swap its form.
  const [acting, setActing] = useState<LockedSection | undefined>(undefined)

  const contentSections = version.content.sections ?? []
  const titleOf = (sectionId: string) =>
    contentSections.find((s) => s.id === sectionId)?.title ?? sectionId

  const requests = report.data?.requests ?? []
  const deviations = report.data?.deviations ?? []
  const active = requests.filter((r) => r.active)
  const ids = [
    ...contentSections.filter((s) => s.locked).map((s) => s.id),
    ...active.map((r) => r.sectionId),
    ...deviations.map((d) => d.section),
  ]
  const sections: LockedSection[] = [...new Set(ids)].map((id) => {
    const changes = deviations.filter((d) => d.section === id)
    return {
      id,
      title: titleOf(id),
      request: active.find((r) => r.sectionId === id),
      changed: changes.length > 0,
      unauthorized: changes.some((d) => !d.authorized),
    }
  })
  // On a draft every locked section is a place to act; later only those with a request say anything.
  const listed = editable ? sections : sections.filter((s) => s.request || s.unauthorized)

  return (
    <Card data-testid="deviations-panel">
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
        <div className="space-y-1.5">
          <CardTitle>{t('deviations.title')}</CardTitle>
          <CardDescription>
            {editable ? t('deviations.draftHint') : t('deviations.versionHint')}
          </CardDescription>
        </div>
        {report.data?.requiresExtraReview && <Badge>{t('deviations.extraReview')}</Badge>}
      </CardHeader>
      <CardContent className="space-y-4">
        {report.isPending && <LoadingState label={t('deviations.loading')} rows={3} />}
        {report.isError && (
          <ErrorState error={report.error} onRetry={() => void report.refetch()} />
        )}
        {report.data && (
          <>
            {listed.length > 0 ? (
              <section className="space-y-2">
                <h3 className="text-sm font-medium">
                  {editable ? t('deviations.lockedSections') : t('deviations.requestsHeading')}
                </h3>
                <ul className="space-y-2">
                  {listed.map((section) => (
                    <SectionRow
                      key={section.id}
                      section={section}
                      editable={editable}
                      onAct={() => {
                        setActing(section)
                      }}
                    />
                  ))}
                </ul>
              </section>
            ) : (
              editable && (
                <p className="text-sm text-muted-foreground">{t('deviations.noLockedSections')}</p>
              )
            )}
            <Report deviations={deviations} label={report.data.version} titleOf={titleOf} />
            <WithdrawnRequests requests={requests.filter((r) => !r.active)} titleOf={titleOf} />
          </>
        )}
      </CardContent>
      {editable && acting && (
        <SectionDialog
          documentId={documentId}
          section={acting}
          onClose={() => {
            setActing(undefined)
          }}
        />
      )}
    </Card>
  )
}
