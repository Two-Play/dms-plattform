import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import type { components } from './schema'

export type CatalogEntry = components['schemas']['CatalogEntry']
export type TemplateFields = components['schemas']['TemplateFieldsView']
export type TemplateField = components['schemas']['TemplateFieldView']
export type FieldType = components['schemas']['FieldType']
export type MetadataValue = components['schemas']['MetadataValue']
export type Preview = components['schemas']['PreviewView']
export type PreviewIssue = components['schemas']['PreviewIssueView']
export type MissingValue = components['schemas']['MissingValueView']

/** Document languages the backend formats values for. */
export type DocumentLanguage = 'en' | 'de'

/** Released template versions (GET /api/v1/templates). */
export function catalogQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['templates', 'catalog'],
    queryFn: async () => {
      const { data, response } = await client.GET('/api/v1/templates')
      if (!response.ok || !data) {
        throw new Error(`Loading the template catalog failed with HTTP ${response.status}`)
      }
      return data
    },
  })
}

/** Form fields of a released template version. */
export function templateFieldsQuery(client: ApiClient, templateId: string, version: number) {
  return queryOptions({
    queryKey: ['templates', templateId, version, 'fields'],
    queryFn: async () => {
      const { data, response } = await client.GET(
        '/api/v1/templates/{id}/versions/{version}/fields',
        { params: { path: { id: templateId, version } } },
      )
      if (!response.ok || !data) {
        throw new Error(`Loading the template fields failed with HTTP ${response.status}`)
      }
      return data
    },
    // Released versions never change.
    staleTime: Infinity,
  })
}

/** Live preview of a released template version with the values entered so far (US-03.2). */
export function previewQuery(
  client: ApiClient,
  templateId: string,
  version: number,
  values: Record<string, MetadataValue>,
  language: DocumentLanguage,
) {
  return queryOptions({
    queryKey: ['templates', templateId, version, 'preview', language, values],
    queryFn: async ({ signal }) => {
      const { data, response } = await client.POST(
        '/api/v1/templates/{id}/versions/{version}/preview',
        { params: { path: { id: templateId, version } }, body: { values, language }, signal },
      )
      if (!response.ok || !data) {
        throw new Error(`The preview failed with HTTP ${response.status}`)
      }
      return data
    },
    staleTime: Infinity,
    retry: false,
  })
}
