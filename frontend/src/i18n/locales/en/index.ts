import * as base from './base'
import * as pages from './pages'

/**
 * English messages. The source catalog: its keys and `{placeholders}` define what every other
 * language must provide (`Translation<Messages>`).
 */
export const en = { ...base, ...pages }

export type Messages = typeof en
