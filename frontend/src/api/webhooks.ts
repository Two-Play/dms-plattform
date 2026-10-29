import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/** A webhook subscription of a connected system (DMS-58); never contains the secret. */
export type Webhook = components['schemas']['WebhookView']

/** Settings of a subscription for creating and changing it. */
export type WebhookInput = components['schemas']['WebhookRequest']

/** A subscription with its signing secret: only the answer to creation and rotation (shown once). */
export type WebhookWithSecret = components['schemas']['WebhookWithSecret']

/** One entry of the delivery log. */
export type WebhookDelivery = components['schemas']['WebhookDeliveryView']

/** Outcome of a signed test event (DMS-107). */
export type WebhookTestResult = components['schemas']['WebhookTestResult']

/** The event types a subscription can choose (`WebhookEventType.all`). */
export const webhookEventTypes = [
  'document.published',
  'document.superseded',
  'document.withdrawn',
] as const

/** The tenant's subscriptions, oldest first (GET /api/v1/admin/webhooks, admins). */
export function webhooksQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['webhooks'],
    queryFn: async (): Promise<Webhook[]> => {
      const { data, error, response } = await client.GET('/api/v1/admin/webhooks')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the webhooks failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** The latest deliveries of a subscription, newest first. */
export function webhookDeliveriesQuery(client: ApiClient, id: string, limit = 20) {
  return queryOptions({
    queryKey: ['webhooks', id, 'deliveries', limit],
    queryFn: async (): Promise<WebhookDelivery[]> => {
      const { data, error, response } = await client.GET('/api/v1/admin/webhooks/{id}/deliveries', {
        params: { path: { id }, query: { limit } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the deliveries failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Subscribes a receiver; 422 for unsafe URLs or unknown event types. Returns the secret once. */
export async function createWebhook(
  client: ApiClient,
  input: WebhookInput,
): Promise<WebhookWithSecret> {
  const { data, error, response } = await client.POST('/api/v1/admin/webhooks', { body: input })
  if (!response.ok || !data) {
    throw requestError(response, error, `Creating the webhook failed with HTTP ${response.status}`)
  }
  return data
}

/** Changes a subscription; `active: false` pauses it. The secret stays. */
export async function changeWebhook(
  client: ApiClient,
  id: string,
  input: WebhookInput,
): Promise<Webhook> {
  const { data, error, response } = await client.PUT('/api/v1/admin/webhooks/{id}', {
    params: { path: { id } },
    body: input,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Changing the webhook failed with HTTP ${response.status}`)
  }
  return data
}

/** Deletes a subscription; its delivery log is kept. */
export async function deleteWebhook(client: ApiClient, id: string): Promise<void> {
  const { error, response } = await client.DELETE('/api/v1/admin/webhooks/{id}', {
    params: { path: { id } },
  })
  if (!response.ok) {
    throw requestError(response, error, `Deleting the webhook failed with HTTP ${response.status}`)
  }
}

/** Replaces the signing secret and returns the new one once. */
export async function rotateWebhookSecret(
  client: ApiClient,
  id: string,
): Promise<WebhookWithSecret> {
  const { data, error, response } = await client.POST('/api/v1/admin/webhooks/{id}/secret', {
    params: { path: { id } },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Rotating the webhook secret failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Sends a signed test event right away (POST …/{id}/test). */
export async function testWebhook(client: ApiClient, id: string): Promise<WebhookTestResult> {
  const { data, error, response } = await client.POST('/api/v1/admin/webhooks/{id}/test', {
    params: { path: { id } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Testing the webhook failed with HTTP ${response.status}`)
  }
  return data
}

/** Sends a dead or delivered delivery again (409 while pending or retrying). */
export async function redeliverWebhook(
  client: ApiClient,
  id: string,
  deliveryId: string,
): Promise<WebhookDelivery> {
  const { data, error, response } = await client.POST(
    '/api/v1/admin/webhooks/{id}/deliveries/{deliveryId}/redeliver',
    { params: { path: { id, deliveryId } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Sending the delivery again failed with HTTP ${response.status}`,
    )
  }
  return data
}
