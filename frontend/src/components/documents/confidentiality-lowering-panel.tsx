import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import {
  applyLowerings,
  levelsBelow,
  loweringQuery,
  MAX_LOWERING_REASON,
  requestLowering,
  withdrawLowering,
  type Lowering,
  type Lowerings,
} from '@/api/confidentiality-lowering'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentVersionView } from '@/api/partners'
import { errorText } from '@/components/shared/error-messages'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t, tOptional } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'

const ON_ITS_WAY = new Set(['Draft', 'InReview', 'InApproval', 'Approved'])

function levelName(level: string): string {
  return tOptional(`docs.meta.levels.${level}`) ?? level
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
    if (error.status === 403) return t(`lowering.${action}.forbidden`)
    if (error.status === 409) return t(`lowering.${action}.conflict`)
  }
  return t(`lowering.${action}.failed`, { message: errorText(error) })
}

function RequestForm({
  documentId,
  basisLevel,
  onDone,
}: {
  documentId: string
  basisLevel: string
  onDone: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const levels = levelsBelow(basisLevel)
  const [level, setLevel] = useState(levels[0] ?? '')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const trimmed = reason.trim()
  const tooLong = trimmed.length > MAX_LOWERING_REASON
  const missing = trimmed === ''

  const ask = useMutation({
    mutationFn: () => requestLowering(client, documentId, { level, reason: trimmed }),
    onSuccess: async (result) => {
      await applyLowerings(queryClient, documentId, result)
      toast(t('lowering.request.done'))
      onDone()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (missing || tooLong) return
    ask.mutate()
  }

  const reasonError = attempted && (missing || tooLong)
  return (
    <form onSubmit={submit} className="grid gap-3" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-level`}>{t('lowering.request.level')}</Label>
        <select
          id={`${id}-level`}
          value={level}
          onChange={(e) => {
            setLevel(e.target.value)
          }}
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          {levels.map((l) => (
            <option key={l} value={l}>
              {levelName(l)}
            </option>
          ))}
        </select>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-reason`}>{t('lowering.request.reason')}</Label>
        <Textarea
          id={`${id}-reason`}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-invalid={reasonError || undefined}
          aria-required
          aria-describedby={`${id}-reason-hint`}
        />
        <p
          id={`${id}-reason-hint`}
          className={reasonError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
          role={reasonError ? 'alert' : undefined}
        >
          {reasonError
            ? tooLong
              ? t('lowering.request.reasonTooLong', { max: MAX_LOWERING_REASON })
              : t('lowering.request.reasonMissing')
            : t('lowering.request.hint')}
        </p>
      </div>
      {ask.isError && <InlineError error={ask.error} text={failure(ask.error, 'request')} />}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={ask.isPending}>
          {ask.isPending ? t('lowering.request.sending') : t('lowering.request.send')}
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={onDone}>
          {t('common.cancel')}
        </Button>
      </div>
    </form>
  )
}

/** Who ended a request, with the reason of the decision if there is one. */
function Closed({ request }: { request: Lowering }) {
  if (!request.decidedBy) return null
  const date = request.decidedAt ? formatDateTime(request.decidedAt) : ''
  const text =
    request.status === 'Approved'
      ? t('lowering.approvedBy', { name: '{name}', date })
      : request.status === 'Refused'
        ? t('lowering.refusedBy', { name: '{name}', date })
        : t('lowering.withdrawnBy', { name: '{name}', date })
  return (
    <>
      <p className="text-muted-foreground">
        <WithName text={text} id={request.decidedBy} />
        {request.actingFor && (
          <>
            {' '}
            <WithName text={t('lowering.actingFor', { name: '{name}' })} id={request.actingFor} />
          </>
        )}
      </p>
      {request.decisionReason && <p>{request.decisionReason}</p>}
    </>
  )
}

function statusText(request: Lowering, lowerings: Lowerings): string {
  switch (request.status) {
    case 'Requested':
      return lowerings.status === 'Draft'
        ? t('lowering.state.draft')
        : t('lowering.state.inWorkflow')
    case 'Approved':
      return lowerings.accessLevel === lowerings.level
        ? t('lowering.state.inForce', { level: levelName(request.to) })
        : t('lowering.state.approved', {
            level: levelName(request.to),
            access: levelName(lowerings.accessLevel),
          })
    case 'Refused':
      return t('lowering.state.refused', { level: levelName(request.from) })
    default:
      return t('lowering.state.withdrawn')
  }
}

function RequestItem({ request, lowerings }: { request: Lowering; lowerings: Lowerings }) {
  return (
    <li className="grid gap-1 rounded-lg border p-3 text-sm" data-testid="lowering-request">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">
          {t('lowering.fromTo', { from: levelName(request.from), to: levelName(request.to) })}
        </span>
        <Badge variant="secondary">
          {tOptional(`lowering.status.${request.status}`) ?? request.status}
        </Badge>
      </div>
      <p>{statusText(request, lowerings)}</p>
      <p>{request.reason}</p>
      <p className="text-muted-foreground">
        <WithName
          text={t('lowering.requestedBy', {
            name: '{name}',
            date: formatDateTime(request.requestedAt),
          })}
          id={request.requestedBy}
        />
      </p>
      <Closed request={request} />
    </li>
  )
}

/**
 * Lowering the confidentiality of a later version (business concept 3.2, ADR 0083): asking on the draft, the
 * state of the requests, and for approvers what they decide on in the additional approval step. Nothing shows
 * for a version that has no request and cannot get one.
 */
export function ConfidentialityLoweringPanel({
  documentId,
  version,
  editable,
}: {
  documentId: string
  version: DocumentVersionView
  /** The shown version is the open draft. */
  editable: boolean
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const lowerings = useQuery(loweringQuery(client, documentId, version.id))
  const [asking, setAsking] = useState(false)

  const withdraw = useMutation({
    mutationFn: () => withdrawLowering(client, documentId),
    onSuccess: async (result) => {
      await applyLowerings(queryClient, documentId, result)
      toast(t('lowering.withdraw.done'))
    },
  })

  if (lowerings.isPending) {
    // Most versions show nothing here: only a version on its way can make room for a panel.
    return ON_ITS_WAY.has(version.status) ? (
      <LoadingState label={t('lowering.loading')} rows={2} />
    ) : null
  }
  if (lowerings.isError) {
    return (
      <ErrorState
        error={lowerings.error}
        titles={{ notFound: t('lowering.failed'), forbidden: t('lowering.failed') }}
        onRetry={() => void lowerings.refetch()}
      />
    )
  }

  const data = lowerings.data
  const requests = data.requests ?? []
  const offered = editable && data.canRequest && data.basisLevel !== undefined
  const open = requests.some((r) => r.status === 'Requested')
  // Whoever is not cleared for the old level gets no requests, and still is told where access stands.
  if (requests.length === 0 && !offered && data.accessLevel === data.level) return null

  return (
    <Card data-testid="confidentiality-lowering">
      <CardHeader>
        <CardTitle>
          <h2>{t('lowering.title')}</h2>
        </CardTitle>
        <CardDescription>
          {data.accessLevel === data.level
            ? t('lowering.hint', { level: levelName(data.level) })
            : t('lowering.accessHint', {
                level: levelName(data.level),
                access: levelName(data.accessLevel),
              })}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3">
        {requests.length > 0 && (
          <ul className="grid gap-2">
            {requests.toReversed().map((request) => (
              <RequestItem key={request.id} request={request} lowerings={data} />
            ))}
          </ul>
        )}
        {editable && open && (
          <div className="grid gap-2">
            {withdraw.isError && (
              <InlineError error={withdraw.error} text={failure(withdraw.error, 'withdraw')} />
            )}
            <Button
              size="sm"
              variant="outline"
              className="justify-self-start"
              disabled={withdraw.isPending}
              onClick={() => {
                withdraw.mutate()
              }}
            >
              {t('lowering.withdraw.action')}
            </Button>
          </div>
        )}
        {offered &&
          data.basisLevel !== undefined &&
          (asking ? (
            <RequestForm
              documentId={documentId}
              basisLevel={data.basisLevel}
              onDone={() => {
                setAsking(false)
              }}
            />
          ) : (
            <div className="grid gap-2">
              <p className="text-sm text-muted-foreground">
                {t('lowering.offer', { level: levelName(data.basisLevel) })}
              </p>
              <Button
                size="sm"
                variant="outline"
                className="justify-self-start"
                onClick={() => {
                  setAsking(true)
                }}
              >
                {t('lowering.request.action')}
              </Button>
            </div>
          ))}
      </CardContent>
    </Card>
  )
}

/** What a version in the additional approval step asks for, shown above the decision. */
export function LoweringDecisionNote({
  documentId,
  versionId,
}: {
  documentId: string
  versionId: string
}) {
  const client = useApiClient()
  const lowerings = useQuery(loweringQuery(client, documentId, versionId))
  if (lowerings.isPending) return <LoadingState label={t('lowering.loading')} rows={1} />
  if (lowerings.isError) {
    return (
      <ErrorState
        error={lowerings.error}
        titles={{ notFound: t('lowering.failed'), forbidden: t('lowering.failed') }}
        onRetry={() => void lowerings.refetch()}
      />
    )
  }
  const request = lowerings.data.requests?.find((r) => r.status === 'Requested')
  if (!request) return null
  return (
    <div className="grid gap-1 rounded-lg border p-3 text-sm" data-testid="lowering-decision">
      <p className="font-medium">
        {t('lowering.fromTo', { from: levelName(request.from), to: levelName(request.to) })}
      </p>
      <p>{request.reason}</p>
      <p className="text-muted-foreground">
        <WithName
          text={t('lowering.requestedBy', {
            name: '{name}',
            date: formatDateTime(request.requestedAt),
          })}
          id={request.requestedBy}
        />
      </p>
      <p className="text-xs text-muted-foreground">{t('lowering.decisionHint')}</p>
    </div>
  )
}
