/** The version shown on a template page, kept in the URL; without it the latest version is shown. */
export interface TemplateSearch {
  version?: number
}

/** Reads the search parameters of `/templates/$templateId`, dropping an invalid version. */
export function parseTemplateSearch(raw: Record<string, unknown>): TemplateSearch {
  const v = typeof raw.version === 'string' ? Number(raw.version) : raw.version
  return typeof v === 'number' && Number.isInteger(v) && v > 0 ? { version: v } : {}
}
