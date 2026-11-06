import { ApiRequestError } from '@/api/errors'
import { errorCode, type RenditionError } from '@/api/renditions'
import { t } from '@/i18n'
import { errorText } from '@/components/shared/error-messages'

/** The cause of a failed attempt in the caller's language, by the backend's failure code. */
export function renditionCause(error: RenditionError | undefined): string {
  const code = errorCode(error)
  return code ? t(`renditions.causes.${code}`) : t('renditions.causes.unknown')
}

/** A failed retry in the caller's language, with the backend's message where it explains the cause. */
export function retryFailure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('renditions.retryForbidden')
    if (error.status === 409) return t('renditions.retryConflict', { message: errorText(error) })
  }
  return t('renditions.retryFailed', { message: errorText(error) })
}
