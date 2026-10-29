import { ApiRequestError } from '@/api/errors'
import type {
  ContentLocation,
  TemplateContent,
  TemplateStatus,
  TemplateVersion,
} from '@/api/template-admin'
import { t } from '@/i18n'

/** A status change offered on a version, mirroring `TemplateLifecycle` in the backend. */
export interface StatusAction {
  to: TemplateStatus
  label: string
}

const actions: Record<string, ('Testing' | 'Draft' | 'Released' | 'Deprecated' | 'Archived')[]> = {
  Draft: ['Testing'],
  Testing: ['Draft', 'Released'],
  Released: ['Deprecated'],
  Deprecated: ['Archived'],
}

/** The status changes the backend allows from `status`, labelled in the active language. */
export function statusActions(status: string): StatusAction[] {
  return (actions[status] ?? []).map((to) => ({ to, label: t(`templates.actions.${to}`) }))
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
  const parts = [
    l.sectionId
      ? t('templates.location.section', { id: l.sectionId })
      : t('templates.location.computed'),
  ]
  if (l.blockPath) parts.push(l.blockPath)
  if (l.part) parts.push(l.part)
  return t('templates.location.position', {
    path: parts.join(' › '),
    line: String(l.line),
    column: String(l.column),
  })
}

/** User-facing message for a failed status change, content save or test run. */
export function errorMessage(error: Error): string {
  if (!(error instanceof ApiRequestError)) return error.message
  switch (error.code) {
    case 'segregation_of_duties':
      return t('templates.errors.fourEyes', { message: error.message })
    case 'forbidden':
      return t('templates.errors.forbidden', { message: error.message })
    case 'lifecycle':
      return t('templates.errors.lifecycle', { message: error.message })
    case 'conflict':
      return t('templates.errors.conflict', { message: error.message })
    default:
      return error.message
  }
}

/** The content as the editor shows it: indented JSON. */
export function contentText(content: TemplateContent): string {
  return JSON.stringify(content, null, 2)
}
