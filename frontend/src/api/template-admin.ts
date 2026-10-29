import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type TemplateVersion = components['schemas']['TemplateVersionView']
export type TemplateContent = components['schemas']['TemplateContent']
export type TemplateRequest = components['schemas']['TemplateRequest']
export type CheckResult = components['schemas']['CheckResult']
export type ContentIssue = components['schemas']['ContentIssueView']
export type ContentLocation = components['schemas']['LocationView']
export type TestRun = components['schemas']['TestRunView']
export type TestCase = components['schemas']['TestCaseView']
export type Branch = components['schemas']['BranchView']
export type TemplateImpact = components['schemas']['TemplateImpactView']
export type OpenDraft = components['schemas']['OpenDraftView']
export type StatusCount = components['schemas']['StatusCountView']
export type TemplateClass = components['schemas']['TemplateClassView']

/** Statuses of a template version (business concept 4.1). */
export type TemplateStatus = 'Draft' | 'Testing' | 'Released' | 'Deprecated' | 'Archived'

const admin = '/api/v1/admin/templates'

/** Latest version of every template, in any status (template designers). */
export function templateAdminListQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['template-admin', 'list'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(admin)
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the templates failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/**
 * The document classes a template can be created for (DMS-121): latest version, label and path, read-only
 * for template designers.
 */
export function templateClassesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['template-admin', 'classes'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/admin/template-classes')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the document classes failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** All versions of a template, oldest first. */
export function templateVersionsQuery(client: ApiClient, templateId: string) {
  return queryOptions({
    queryKey: ['template-admin', templateId, 'versions'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/admin/templates/{id}', {
        params: { path: { id: templateId } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the template failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/**
 * Fields of a version in any status (designer endpoint). Content that does not compile answers 422;
 * the caller shows the message instead of the fields.
 */
export function designerFieldsQuery(client: ApiClient, templateId: string, version: number) {
  return queryOptions({
    queryKey: ['template-admin', templateId, version, 'fields'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/admin/templates/{id}/versions/{version}/fields',
        { params: { path: { id: templateId, version } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the fields failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Impact analysis before releasing a version (DMS-50): documents on earlier versions, open drafts. */
export function templateImpactQuery(client: ApiClient, templateId: string, version: number) {
  return queryOptions({
    queryKey: ['template-admin', templateId, version, 'impact'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/templates/{id}/versions/{version}/impact',
        { params: { path: { id: templateId, version } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the impact analysis failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    // Documents change all the time: always ask again when the dialog opens.
    staleTime: 0,
    retry: false,
  })
}

/** Checks content against the class profile without saving; issues carry their location. */
export async function checkContent(
  client: ApiClient,
  documentClassId: string,
  documentClassVersion: number,
  content: TemplateContent,
): Promise<CheckResult> {
  const { data, error, response } = await client.POST('/api/v1/admin/templates/check', {
    body: { documentClassId, documentClassVersion, content },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `The check failed with HTTP ${response.status}`)
  }
  return data
}

/** Saves a draft version (PUT); 422 `invalid_content` for content that does not compile. */
export async function saveDraft(
  client: ApiClient,
  templateId: string,
  version: number,
  body: TemplateRequest,
): Promise<TemplateVersion> {
  const { data, error, response } = await client.PUT(
    '/api/v1/admin/templates/{id}/versions/{version}',
    { params: { path: { id: templateId, version } }, body },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the draft failed with HTTP ${response.status}`)
  }
  return data
}

/** Creates the next version as a draft; 409 while another version is open. */
export async function createVersion(
  client: ApiClient,
  templateId: string,
  body: TemplateRequest,
): Promise<TemplateVersion> {
  const { data, error, response } = await client.POST('/api/v1/admin/templates/{id}/versions', {
    params: { path: { id: templateId } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Creating a new version failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Test mode with generated sample data (every branch, empty optionals, long values). */
export async function runTest(
  client: ApiClient,
  templateId: string,
  version: number,
  language: 'en' | 'de',
): Promise<TestRun> {
  const { data, error, response } = await client.POST(
    '/api/v1/admin/templates/{id}/versions/{version}/test',
    { params: { path: { id: templateId, version } }, body: { language } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The test mode failed with HTTP ${response.status}`)
  }
  return data
}

/** Status change; releasing needs a designer who neither created nor changed the version (403). */
export async function changeTemplateStatus(
  client: ApiClient,
  templateId: string,
  version: number,
  status: TemplateStatus,
): Promise<TemplateVersion> {
  const { data, error, response } = await client.POST(
    '/api/v1/admin/templates/{id}/versions/{version}/status',
    { params: { path: { id: templateId, version } }, body: { status } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The status change failed with HTTP ${response.status}`)
  }
  return data
}

/** The request body that keeps a version's name, class and content. */
/** Creates a template with version 1 as a draft (POST /api/v1/admin/templates). */
export async function createTemplate(
  client: ApiClient,
  body: TemplateRequest,
): Promise<TemplateVersion> {
  const { data, error, response } = await client.POST('/api/v1/admin/templates', { body })
  if (!response.ok || !data) {
    throw requestError(response, error, `Creating the template failed with HTTP ${response.status}`)
  }
  return data
}

export function requestOf(
  v: TemplateVersion,
  content: TemplateContent = v.content,
): TemplateRequest {
  return {
    name: v.name,
    description: v.description,
    documentClassId: v.documentClassId,
    documentClassVersion: v.documentClassVersion,
    content,
  }
}
