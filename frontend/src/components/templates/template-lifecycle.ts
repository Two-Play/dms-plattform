import { ApiRequestError } from '@/api/errors'
import type {
  ContentLocation,
  TemplateContent,
  TemplateStatus,
  TemplateVersion,
} from '@/api/template-admin'

/** A status change offered on a version, mirroring `TemplateLifecycle` in the backend. */
export interface StatusAction {
  to: TemplateStatus
  label: string
}

const actions: Record<string, StatusAction[]> = {
  Draft: [{ to: 'Testing', label: 'Start testing' }],
  Testing: [
    { to: 'Draft', label: 'Back to draft' },
    { to: 'Released', label: 'Release…' },
  ],
  Released: [{ to: 'Deprecated', label: 'Deprecate…' }],
  Deprecated: [{ to: 'Archived', label: 'Archive' }],
}

/** The status changes the backend allows from `status`. */
export function statusActions(status: string): StatusAction[] {
  return actions[status] ?? []
}

/** Whether a template has a version that is being worked on (Draft or Testing); at most one at a time. */
export function hasOpenVersion(versions: TemplateVersion[]): boolean {
  return versions.some((v) => v.status === 'Draft' || v.status === 'Testing')
}

/** Who created or changed a version: the four-eyes principle keeps them from releasing it. */
export function contributors(v: TemplateVersion): string[] {
  return [...new Set([v.createdBy, ...(v.editedBy ?? [])])]
}

/** Readable location of a content issue, e.g. "Section main › /blocks/1 › condition, line 1, column 5". */
export function locationLabel(l: ContentLocation): string {
  const parts = [l.sectionId ? `Section ${l.sectionId}` : 'Computed fields']
  if (l.blockPath) parts.push(l.blockPath)
  if (l.part) parts.push(l.part)
  return `${parts.join(' › ')}, line ${String(l.line)}, column ${String(l.column)}`
}

/** User-facing message for a failed status change, content save or test run. */
export function errorMessage(error: Error): string {
  if (!(error instanceof ApiRequestError)) return error.message
  switch (error.code) {
    case 'segregation_of_duties':
      return `Four-eyes principle: a version can only be released by a template designer who neither created nor changed it. ${error.message}`
    case 'forbidden':
      return `You are not allowed to do this. ${error.message}`
    case 'lifecycle':
      return `This status change is not possible: ${error.message}`
    case 'conflict':
      return `${error.message} Reload the page to see the current state.`
    default:
      return error.message
  }
}

/** The content as the editor shows it: indented JSON. */
export function contentText(content: TemplateContent): string {
  return JSON.stringify(content, null, 2)
}
