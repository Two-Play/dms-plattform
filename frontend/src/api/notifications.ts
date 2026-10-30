import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * In-app notifications and notification preferences (DMS-130, ADR 0052). The bell in the header shows
 * the unread count and the newest notifications; email per category and the language are set on the
 * settings page.
 */
export type Notification = components['schemas']['NotificationView']
export type NotificationPage = components['schemas']['NotificationPageView']
export type NotificationPreferences = components['schemas']['NotificationPreferencesView']
export type ChangeNotificationPreferences = components['schemas']['ChangeNotificationPreferences']
export type OwnedAcknowledgement = components['schemas']['OwnedAcknowledgementView']

/** Categories in the order the settings page lists them. */
export const notificationCategories = [
  'workflow',
  'acknowledgement',
  'deputyship',
  'publication',
  'legal-hold',
] as const
export type NotificationCategory = (typeof notificationCategories)[number]

/** How often the bell asks for new notifications. */
export const POLL_INTERVAL_MS = 60_000

const keys = {
  all: ['notifications'] as const,
  unread: ['notifications', 'unread-count'] as const,
  page: (unreadOnly: boolean, offset: number, limit: number) =>
    ['notifications', 'page', unreadOnly, offset, limit] as const,
  preferences: ['notifications', 'preferences'] as const,
}

export const notificationKeys = keys

/** The number of unread notifications, polled while the app is open. */
export function unreadCountQuery(client: ApiClient) {
  return queryOptions({
    queryKey: keys.unread,
    queryFn: async (): Promise<number> => {
      const { data, error, response } = await client.GET('/api/v1/notifications/unread-count')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the unread notifications failed with HTTP ${response.status}`,
        )
      }
      return data.unread
    },
    refetchInterval: POLL_INTERVAL_MS,
  })
}

/** A page of the caller's notifications, newest first (GET /api/v1/notifications). */
export function notificationsQuery(
  client: ApiClient,
  {
    unreadOnly = false,
    offset = 0,
    limit = 20,
  }: { unreadOnly?: boolean; offset?: number; limit?: number } = {},
) {
  return queryOptions({
    queryKey: keys.page(unreadOnly, offset, limit),
    queryFn: async (): Promise<NotificationPage> => {
      const { data, error, response } = await client.GET('/api/v1/notifications', {
        params: { query: { unread: unreadOnly || undefined, offset, limit } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the notifications failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Marks one notification as read (POST /api/v1/notifications/{id}/read). */
export async function markNotificationRead(client: ApiClient, id: string): Promise<void> {
  const { error, response } = await client.POST('/api/v1/notifications/{id}/read', {
    params: { path: { id } },
  })
  if (!response.ok) {
    throw requestError(response, error, `Marking as read failed with HTTP ${response.status}`)
  }
}

/** Marks all notifications as read; returns how many were unread. */
export async function markAllNotificationsRead(client: ApiClient): Promise<number> {
  const { data, error, response } = await client.POST('/api/v1/notifications/read-all')
  if (!response.ok || !data) {
    throw requestError(response, error, `Marking as read failed with HTTP ${response.status}`)
  }
  return data.marked
}

export function notificationPreferencesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: keys.preferences,
    queryFn: async (): Promise<NotificationPreferences> => {
      const { data, error, response } = await client.GET('/api/v1/notifications/preferences')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the notification settings failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Saves language and email choices (PUT /api/v1/notifications/preferences). */
export async function saveNotificationPreferences(
  client: ApiClient,
  change: ChangeNotificationPreferences,
): Promise<NotificationPreferences> {
  const { data, error, response } = await client.PUT('/api/v1/notifications/preferences', {
    body: change,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Saving the notification settings failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Valid versions of the caller's documents with open acknowledgements (owner dashboard). */
export function ownedAcknowledgementsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['acknowledgements', 'owned'],
    queryFn: async (): Promise<OwnedAcknowledgement[]> => {
      const { data, error, response } = await client.GET('/api/v1/acknowledgements/owned')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the open acknowledgements of your documents failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}
