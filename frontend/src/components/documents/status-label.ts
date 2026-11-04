import { tOptional } from '@/i18n'

/** The status to show of a version: one withdrawn before it took effect is told from one taken out of force. */
export function versionStatus(version: { status: string; withdrawal?: unknown }): string {
  return version.withdrawal && version.status === 'Withdrawn'
    ? 'WithdrawnBeforeValid'
    : version.status
}

/**
 * Readable form of a status, kind or decision from the API in the active language, e.g. `InReview`
 * → "In review"; unknown values are split into words (`SomethingNew` → "Something new").
 */
export function statusLabel(status: string): string {
  const known = tOptional(`status.${status}`)
  if (known !== undefined) return known
  const words = status.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
