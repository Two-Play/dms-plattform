import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/** A number range with the number the next document would get (DMS-113). */
export type NumberRange = components['schemas']['NumberRangeView']

/** Settings of a new range; the key is fixed afterwards. */
export type NewNumberRange = components['schemas']['CreateNumberRange']

/** New settings of a range; they only affect numbers assigned afterwards. */
export type NumberRangeChange = components['schemas']['ChangeNumberRange']

export type NumberRangePreview = components['schemas']['NumberRangePreview']

/** The own and the effective number range of a document class. */
export type ClassNumberRange = components['schemas']['ClassNumberRangeView']

/** How a class gets its numbers: from its parent, explicitly none, or an own range. */
export type AssignmentMode = 'inherit' | 'none' | 'range'

export type DocumentNumberResult = components['schemas']['DocumentNumberView']

/** The number ranges of the tenant, by key (GET /api/v1/admin/number-ranges, admins). */
export function numberRangesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['number-ranges'],
    queryFn: async (): Promise<NumberRange[]> => {
      const { data, error, response } = await client.GET('/api/v1/admin/number-ranges')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the number ranges failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Every class with its own and effective range (GET /api/v1/admin/number-range-assignments). */
export function classNumberRangesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['number-ranges', 'classes'],
    queryFn: async (): Promise<ClassNumberRange[]> => {
      const { data, error, response } = await client.GET('/api/v1/admin/number-range-assignments')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the class assignments failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Checks a pattern and returns the first number of a period this year (POST …/preview). */
export async function previewNumberRange(
  client: ApiClient,
  pattern: string,
  startValue: number,
  yearlyReset: boolean,
): Promise<NumberRangePreview> {
  const { data, error, response } = await client.POST('/api/v1/admin/number-ranges/preview', {
    body: { pattern, startValue, yearlyReset },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Checking the pattern failed with HTTP ${response.status}`)
  }
  return data
}

/** Creates a range; 422 for invalid settings, 409 for a taken key. */
export async function createNumberRange(
  client: ApiClient,
  range: NewNumberRange,
): Promise<NumberRange> {
  const { data, error, response } = await client.POST('/api/v1/admin/number-ranges', {
    body: range,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Creating the number range failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Changes a range (PUT /api/v1/admin/number-ranges/{id}); numbers already assigned stay. */
export async function changeNumberRange(
  client: ApiClient,
  id: string,
  change: NumberRangeChange,
): Promise<NumberRange> {
  const { data, error, response } = await client.PUT('/api/v1/admin/number-ranges/{id}', {
    params: { path: { id } },
    body: change,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Changing the number range failed with HTTP ${response.status}`,
    )
  }
  return data
}

/**
 * Sets how a class gets its numbers: `inherit` removes its own assignment, `none` stops an
 * inherited range, `range` assigns `rangeId`.
 */
export async function assignClassNumberRange(
  client: ApiClient,
  classId: string,
  mode: AssignmentMode,
  rangeId?: string,
): Promise<ClassNumberRange> {
  const { data, error, response } =
    mode === 'inherit'
      ? await client.DELETE('/api/v1/admin/number-range-assignments/{classId}', {
          params: { path: { classId } },
        })
      : await client.PUT('/api/v1/admin/number-range-assignments/{classId}', {
          params: { path: { classId } },
          body: mode === 'range' ? { numberRangeId: rangeId } : {},
        })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Assigning the number range failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Assigns a document number manually with a reason (POST /api/v1/documents/{id}/number). */
export async function assignDocumentNumber(
  client: ApiClient,
  documentId: string,
  number: string,
  reason: string,
): Promise<DocumentNumberResult> {
  const { data, error, response } = await client.POST('/api/v1/documents/{id}/number', {
    params: { path: { id: documentId } },
    body: { number, reason },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Assigning the document number failed with HTTP ${response.status}`,
    )
  }
  return data
}
