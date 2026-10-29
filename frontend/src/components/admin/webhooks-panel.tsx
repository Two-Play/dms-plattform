import { Copy, Webhook as WebhookIcon } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  changeWebhook,
  createWebhook,
  deleteWebhook,
  redeliverWebhook,
  rotateWebhookSecret,
  testWebhook,
  webhookDeliveriesQuery,
  webhookEventTypes,
  webhooksQuery,
  type Webhook,
  type WebhookDelivery,
  type WebhookInput,
  type WebhookTestResult,
} from '@/api/webhooks'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t, tOptional } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'

interface WebhookFormInput {
  url: string
  eventTypes: string[]
  classes: string
  description: string
  active: boolean
}

const emptyInput: WebhookFormInput = {
  url: '',
  eventTypes: [...webhookEventTypes],
  classes: '',
  description: '',
  active: true,
}

function inputOf(webhook: Webhook): WebhookFormInput {
  return {
    url: webhook.url,
    eventTypes: [...(webhook.eventTypes ?? [])],
    classes: (webhook.documentClasses ?? []).join(', '),
    description: webhook.description ?? '',
    active: webhook.active,
  }
}

function requestOf(input: WebhookFormInput): WebhookInput {
  const description = input.description.trim()
  return {
    url: input.url.trim(),
    eventTypes: input.eventTypes,
    documentClasses: input.classes
      .split(',')
      .map((c) => c.trim())
      .filter((c) => c !== ''),
    ...(description === '' ? {} : { description }),
    active: input.active,
  }
}

/** The host of a receiver URL, to name a webhook in labels and messages. */
function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

/** The label of an event type, e.g. `document.published` → "Published". */
function eventLabel(type: string): string {
  return tOptional(`webhooks.event.${type.replace(/^(document|webhook)\./, '')}`) ?? type
}

function statusLabel(status: string): string {
  return tOptional(`webhooks.deliveryStatus.${status}`) ?? status
}

function urlProblem(url: string): string | undefined {
  const value = url.trim()
  if (value === '') return t('webhooks.urlMissing')
  try {
    const parsed = new URL(value)
    // http is accepted by the backend for development hosts only; it decides.
    return parsed.protocol === 'https:' || parsed.protocol === 'http:'
      ? undefined
      : t('webhooks.urlProblem')
  } catch {
    return t('webhooks.urlProblem')
  }
}

/** The secret right after creation or rotation: the only time it is shown (DMS-107). */
function SecretNotice({ secret, onDone }: { secret: string; onDone: () => void }) {
  const id = useId()
  async function copy() {
    try {
      await navigator.clipboard.writeText(secret)
      toast(t('webhooks.copied'))
    } catch {
      toast(t('webhooks.copyFailed'), 'error')
    }
  }
  return (
    <section
      aria-labelledby={`${id}-title`}
      className="space-y-2 rounded-lg border border-amber-500/50 bg-amber-500/5 p-4 text-sm"
      data-testid="webhook-secret"
    >
      <h3 id={`${id}-title`} className="font-medium">
        {t('webhooks.secretTitle')}
      </h3>
      <p id={`${id}-text`}>{t('webhooks.secretOnce')}</p>
      <div className="flex flex-wrap items-center gap-2">
        <code
          aria-describedby={`${id}-text`}
          className="rounded bg-muted px-2 py-1 font-mono text-xs break-all select-all"
        >
          {secret}
        </code>
        <Button type="button" size="sm" variant="outline" onClick={() => void copy()}>
          <Copy className="size-4" aria-hidden />
          {t('webhooks.copy')}
        </Button>
      </div>
      <Button type="button" size="sm" onClick={onDone}>
        {t('webhooks.secretDone')}
      </Button>
    </section>
  )
}

/** Create a webhook, or edit one (`webhook` given). */
function WebhookForm({
  webhook,
  onSecret,
  onDone,
}: {
  webhook?: Webhook
  onSecret?: (secret: string) => void
  onDone?: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [input, setInput] = useState<WebhookFormInput>(webhook ? inputOf(webhook) : emptyInput)
  const [attempted, setAttempted] = useState(false)
  const problems = {
    url: urlProblem(input.url),
    events: input.eventTypes.length === 0 ? t('webhooks.eventsMissing') : undefined,
  }
  const save = useMutation({
    mutationFn: async () => {
      if (webhook) return { saved: await changeWebhook(client, webhook.id, requestOf(input)) }
      const created = await createWebhook(client, requestOf(input))
      return { saved: created.webhook, secret: created.secret }
    },
    onSuccess: async ({ saved, secret }) => {
      await queryClient.invalidateQueries({ queryKey: ['webhooks'] })
      const host = hostOf(saved.url)
      toast(webhook ? t('webhooks.saved', { host }) : t('webhooks.created', { host }))
      if (secret) onSecret?.(secret)
      if (!webhook) {
        setInput(emptyInput)
        setAttempted(false)
      }
      onDone?.()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problems.url ?? problems.events) return
    save.mutate()
  }

  function toggle(type: string, on: boolean) {
    setInput((current) => ({
      ...current,
      eventTypes: on
        ? webhookEventTypes.filter((e) => e === type || current.eventTypes.includes(e))
        : current.eventTypes.filter((e) => e !== type),
    }))
  }

  const shown = attempted ? problems : { url: undefined, events: undefined }
  return (
    <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2" noValidate>
      <div className="grid gap-1.5 sm:col-span-2">
        <Label htmlFor={`${id}-url`}>{t('webhooks.url')}</Label>
        <Input
          id={`${id}-url`}
          type="url"
          value={input.url}
          placeholder="https://intranet.example.com/hooks/dms"
          onChange={(e) => {
            setInput((c) => ({ ...c, url: e.target.value }))
          }}
          aria-required
          aria-invalid={shown.url ? true : undefined}
          aria-describedby={`${id}-url-hint`}
        />
        <p
          id={`${id}-url-hint`}
          role={shown.url ? 'alert' : undefined}
          className={shown.url ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {shown.url ?? t('webhooks.urlHint')}
        </p>
      </div>
      <fieldset className="grid gap-1.5 sm:col-span-2">
        <legend className="mb-1.5 text-sm font-medium">{t('webhooks.events')}</legend>
        <div className="flex flex-wrap gap-4">
          {webhookEventTypes.map((type) => (
            <div key={type} className="flex items-center gap-2">
              <input
                id={`${id}-${type}`}
                type="checkbox"
                className="size-4"
                checked={input.eventTypes.includes(type)}
                onChange={(e) => {
                  toggle(type, e.target.checked)
                }}
              />
              <Label htmlFor={`${id}-${type}`}>{eventLabel(type)}</Label>
            </div>
          ))}
        </div>
        {shown.events && (
          <p role="alert" className="text-sm text-destructive">
            {shown.events}
          </p>
        )}
      </fieldset>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-classes`}>{t('webhooks.classes')}</Label>
        <Input
          id={`${id}-classes`}
          value={input.classes}
          placeholder="policy"
          onChange={(e) => {
            setInput((c) => ({ ...c, classes: e.target.value }))
          }}
          aria-describedby={`${id}-classes-hint`}
        />
        <p id={`${id}-classes-hint`} className="text-xs text-muted-foreground">
          {t('webhooks.classesHint')}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-description`}>{t('webhooks.description')}</Label>
        <Input
          id={`${id}-description`}
          value={input.description}
          maxLength={500}
          onChange={(e) => {
            setInput((c) => ({ ...c, description: e.target.value }))
          }}
        />
      </div>
      <div className="flex items-start gap-2 sm:col-span-2">
        <input
          id={`${id}-active`}
          type="checkbox"
          className="mt-1 size-4"
          checked={input.active}
          onChange={(e) => {
            setInput((c) => ({ ...c, active: e.target.checked }))
          }}
          aria-describedby={`${id}-active-hint`}
        />
        <div>
          <Label htmlFor={`${id}-active`}>{t('webhooks.active')}</Label>
          <p id={`${id}-active-hint`} className="text-xs text-muted-foreground">
            {t('webhooks.activeHint')}
          </p>
        </div>
      </div>
      <div className="flex gap-2 sm:col-span-2">
        <Button type="submit" disabled={save.isPending}>
          {webhook
            ? save.isPending
              ? t('webhooks.saving')
              : t('webhooks.save')
            : save.isPending
              ? t('webhooks.creating')
              : t('webhooks.create')}
        </Button>
        {webhook && onDone && (
          <Button type="button" variant="outline" onClick={onDone}>
            {t('webhooks.cancel')}
          </Button>
        )}
      </div>
      {save.isError && <InlineError error={save.error} className="sm:col-span-2" />}
    </form>
  )
}

function testMessage(host: string, result: WebhookTestResult): string {
  if (result.delivered) {
    return t('webhooks.testDelivered', {
      host,
      status: result.statusCode ?? 0,
      millis: result.durationMillis,
    })
  }
  if (result.error) return t('webhooks.testFailed', { host, error: result.error })
  return t('webhooks.testFailedStatus', { host, status: result.statusCode ?? 0 })
}

/** The latest deliveries of a webhook with status, attempts and the last answer. */
function Deliveries({ webhook, onClose }: { webhook: Webhook; onClose: () => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const host = hostOf(webhook.url)
  const deliveries = useQuery(webhookDeliveriesQuery(client, webhook.id))
  const redeliver = useMutation({
    mutationFn: (d: WebhookDelivery) => redeliverWebhook(client, webhook.id, d.id),
    onSuccess: async () => {
      toast(t('webhooks.redelivered'))
      await queryClient.invalidateQueries({ queryKey: ['webhooks', webhook.id, 'deliveries'] })
    },
  })
  return (
    <section aria-label={t('webhooks.deliveriesTitle', { host })} className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-medium">{t('webhooks.deliveriesTitle', { host })}</h3>
        <Button type="button" size="sm" variant="outline" onClick={onClose}>
          {t('webhooks.closeDeliveries')}
        </Button>
      </div>
      {deliveries.isPending && <LoadingState label={t('webhooks.loadingDeliveries')} />}
      {deliveries.isError && (
        <ErrorState error={deliveries.error} onRetry={() => void deliveries.refetch()} />
      )}
      {redeliver.isError && <InlineError error={redeliver.error} />}
      {deliveries.data && deliveries.data.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('webhooks.noDeliveries')}</p>
      )}
      {deliveries.data && deliveries.data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">{t('webhooks.deliveriesCaption', { host })}</caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colTime')}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colEvent')}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colDocument')}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colStatus')}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colAttempts')}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  {t('webhooks.colLast')}
                </th>
                <th scope="col" className="py-2 font-medium">
                  <span className="sr-only">{t('webhooks.colActions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {deliveries.data.map((d) => {
                const time = formatDateTime(d.createdAt)
                return (
                  <tr key={d.id} className="border-b align-top last:border-0">
                    <td className="py-2 pr-4 whitespace-nowrap">{time}</td>
                    <td className="py-2 pr-4">{eventLabel(d.eventType)}</td>
                    <td className="py-2 pr-4 font-mono text-xs">
                      {d.documentNumber ?? d.documentId ?? t('common.none')}
                    </td>
                    <td className="py-2 pr-4">
                      <Badge
                        variant={
                          d.status === 'dead'
                            ? 'destructive'
                            : d.status === 'delivered'
                              ? 'secondary'
                              : 'outline'
                        }
                      >
                        {statusLabel(d.status)}
                      </Badge>
                    </td>
                    <td className="py-2 pr-4">{t('webhooks.attempts', { count: d.attempts })}</td>
                    <td className="py-2 pr-4">
                      {d.lastStatusCode !== undefined && (
                        <span className="block">
                          {t('webhooks.lastStatus', { status: d.lastStatusCode })}
                        </span>
                      )}
                      {d.lastError && (
                        <span className="block text-xs break-words text-muted-foreground">
                          {d.lastError}
                        </span>
                      )}
                    </td>
                    <td className="py-2">
                      {d.status !== 'pending' && d.status !== 'retrying' && (
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          disabled={redeliver.isPending}
                          aria-label={t('webhooks.redeliverLabel', { time })}
                          onClick={() => {
                            redeliver.mutate(d)
                          }}
                        >
                          {t('webhooks.redeliver')}
                        </Button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

type Confirmation = { kind: 'rotate' | 'delete'; webhook: Webhook }

/** Confirms rotating a secret or deleting a webhook. */
function ConfirmDialog({
  confirmation,
  onClose,
  onSecret,
}: {
  confirmation?: Confirmation
  onClose: () => void
  onSecret: (secret: string) => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const run = useMutation({
    mutationFn: async ({ kind, webhook }: Confirmation) =>
      kind === 'rotate'
        ? (await rotateWebhookSecret(client, webhook.id)).secret
        : deleteWebhook(client, webhook.id).then(() => undefined),
    onSuccess: async (secret, { kind, webhook }) => {
      const host = hostOf(webhook.url)
      await queryClient.invalidateQueries({ queryKey: ['webhooks'] })
      toast(kind === 'rotate' ? t('webhooks.rotated', { host }) : t('webhooks.deleted', { host }))
      if (secret) onSecret(secret)
      run.reset()
      onClose()
    },
  })
  const host = confirmation ? hostOf(confirmation.webhook.url) : ''
  const rotate = confirmation?.kind === 'rotate'
  return (
    <Dialog
      open={confirmation !== undefined}
      onOpenChange={(open) => {
        if (!open) {
          run.reset()
          onClose()
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {rotate ? t('webhooks.rotateTitle', { host }) : t('webhooks.deleteTitle', { host })}
          </DialogTitle>
          <DialogDescription>
            {rotate ? t('webhooks.rotateText') : t('webhooks.deleteText')}
          </DialogDescription>
        </DialogHeader>
        {run.isError && <InlineError error={run.error} />}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            {t('webhooks.cancel')}
          </Button>
          <Button
            type="button"
            variant={rotate ? 'default' : 'destructive'}
            disabled={run.isPending}
            onClick={() => {
              if (confirmation) run.mutate(confirmation)
            }}
          >
            {rotate ? t('webhooks.rotateConfirm') : t('webhooks.deleteConfirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function WebhookTable({
  webhooks,
  onEdit,
  onDeliveries,
  onConfirm,
}: {
  webhooks: Webhook[]
  onEdit: (webhook: Webhook) => void
  onDeliveries: (webhook: Webhook) => void
  onConfirm: (confirmation: Confirmation) => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const [results, setResults] = useState<Record<string, string>>({})
  const test = useMutation({
    mutationFn: (webhook: Webhook) => testWebhook(client, webhook.id),
    onSuccess: (result, webhook) => {
      setResults((r) => ({ ...r, [webhook.id]: testMessage(hostOf(webhook.url), result) }))
    },
  })
  const toggle = useMutation({
    mutationFn: (webhook: Webhook) =>
      changeWebhook(
        client,
        webhook.id,
        requestOf({ ...inputOf(webhook), active: !webhook.active }),
      ),
    onSuccess: async (saved) => {
      const host = hostOf(saved.url)
      toast(saved.active ? t('webhooks.resumed', { host }) : t('webhooks.paused', { host }))
      await queryClient.invalidateQueries({ queryKey: ['webhooks'] })
    },
  })
  return (
    <div className="space-y-2">
      {test.isError && <InlineError error={test.error} />}
      {toggle.isError && <InlineError error={toggle.error} />}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">{t('webhooks.listCaption')}</caption>
          <thead className="text-left text-xs text-muted-foreground">
            <tr className="border-b">
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('webhooks.colReceiver')}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('webhooks.colEvents')}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('webhooks.colClasses')}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('webhooks.colSecret')}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {t('webhooks.colStatus')}
              </th>
              <th scope="col" className="py-2 font-medium">
                <span className="sr-only">{t('webhooks.colActions')}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {webhooks.map((w) => {
              const host = hostOf(w.url)
              const result = results[w.id]
              return (
                <tr key={w.id} className="border-b align-top last:border-0">
                  <td className="py-2 pr-4">
                    <span className="block font-mono text-xs break-all">{w.url}</span>
                    {w.description && (
                      <span className="block text-xs text-muted-foreground">{w.description}</span>
                    )}
                    {result && (
                      <p role="status" className="mt-1 text-xs" data-testid={`test-${w.id}`}>
                        {result}
                      </p>
                    )}
                  </td>
                  <td className="py-2 pr-4">
                    {(w.eventTypes ?? []).map((e) => eventLabel(e)).join(', ')}
                  </td>
                  <td className="py-2 pr-4">
                    {(w.documentClasses ?? []).length > 0
                      ? (w.documentClasses ?? []).join(', ')
                      : t('webhooks.allClasses')}
                  </td>
                  <td className="py-2 pr-4">
                    <span aria-hidden className="font-mono">
                      whsec_••••••••
                    </span>
                    <span className="sr-only">{t('webhooks.secretMasked')}</span>
                  </td>
                  <td className="py-2 pr-4">
                    <Badge variant={w.active ? 'secondary' : 'outline'}>
                      {w.active ? t('webhooks.statusActive') : t('webhooks.statusPaused')}
                    </Badge>
                  </td>
                  <td className="py-2">
                    <div className="flex flex-wrap gap-1.5">
                      <Button
                        type="button"
                        size="sm"
                        disabled={test.isPending}
                        aria-label={t('webhooks.testLabel', { host })}
                        onClick={() => {
                          test.mutate(w)
                        }}
                      >
                        {test.isPending && test.variables.id === w.id
                          ? t('webhooks.testing')
                          : t('webhooks.test')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={t('webhooks.deliveriesLabel', { host })}
                        onClick={() => {
                          onDeliveries(w)
                        }}
                      >
                        {t('webhooks.deliveries')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={t('webhooks.editLabel', { host })}
                        onClick={() => {
                          onEdit(w)
                        }}
                      >
                        {t('webhooks.edit')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={toggle.isPending}
                        aria-label={
                          w.active
                            ? t('webhooks.pauseLabel', { host })
                            : t('webhooks.resumeLabel', { host })
                        }
                        onClick={() => {
                          toggle.mutate(w)
                        }}
                      >
                        {w.active ? t('webhooks.pause') : t('webhooks.resume')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={t('webhooks.rotateLabel', { host })}
                        onClick={() => {
                          onConfirm({ kind: 'rotate', webhook: w })
                        }}
                      >
                        {t('webhooks.rotate')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        aria-label={t('webhooks.deleteLabel', { host })}
                        onClick={() => {
                          onConfirm({ kind: 'delete', webhook: w })
                        }}
                      >
                        {t('webhooks.delete')}
                      </Button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * Webhooks for connected systems (DMS-107): create, edit, pause and delete subscriptions, rotate
 * their secret (shown once, stored encrypted), send a signed test event and read the delivery log.
 */
export function WebhooksPanel() {
  const client = useApiClient()
  const webhooks = useQuery(webhooksQuery(client))
  const [secret, setSecret] = useState<string>()
  const [editing, setEditing] = useState<Webhook>()
  const [showing, setShowing] = useState<Webhook>()
  const [confirmation, setConfirmation] = useState<Confirmation>()

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>{t('webhooks.newWebhook')}</CardTitle>
          <CardDescription>{t('webhooks.newWebhookText')}</CardDescription>
        </CardHeader>
        <CardContent>
          <WebhookForm onSecret={setSecret} />
        </CardContent>
      </Card>
      {secret && (
        <SecretNotice
          secret={secret}
          onDone={() => {
            setSecret(undefined)
          }}
        />
      )}
      <Card>
        <CardHeader>
          <CardTitle>
            <h2>{t('webhooks.list')}</h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {webhooks.isPending && <LoadingState label={t('webhooks.loading')} rows={3} />}
          {webhooks.isError && (
            <ErrorState error={webhooks.error} onRetry={() => void webhooks.refetch()} />
          )}
          {webhooks.data && webhooks.data.length === 0 && (
            <EmptyState
              icon={WebhookIcon}
              title={t('webhooks.none')}
              description={t('webhooks.noneText')}
            />
          )}
          {webhooks.data && webhooks.data.length > 0 && (
            <WebhookTable
              webhooks={webhooks.data}
              onEdit={setEditing}
              onDeliveries={setShowing}
              onConfirm={setConfirmation}
            />
          )}
          {editing && (
            <section aria-label={t('webhooks.editTitle', { host: hostOf(editing.url) })}>
              <h3 className="mb-3 font-medium">
                {t('webhooks.editTitle', { host: hostOf(editing.url) })}
              </h3>
              <WebhookForm
                key={editing.id}
                webhook={editing}
                onDone={() => {
                  setEditing(undefined)
                }}
              />
            </section>
          )}
          {showing && (
            <Deliveries
              key={showing.id}
              webhook={showing}
              onClose={() => {
                setShowing(undefined)
              }}
            />
          )}
        </CardContent>
      </Card>
      <ConfirmDialog
        confirmation={confirmation}
        onClose={() => {
          setConfirmation(undefined)
        }}
        onSecret={setSecret}
      />
    </div>
  )
}
