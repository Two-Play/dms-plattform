import type { Translation } from '@/i18n/core'
import type { Messages } from '@/i18n/locales/en'
import * as base from './base'
import * as pages from './pages'

/** German messages, the default language (DMS-110). */
export const de: Translation<Messages> = { ...base, ...pages }
