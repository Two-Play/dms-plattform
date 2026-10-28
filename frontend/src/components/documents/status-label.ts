/** Readable form of a status or kind from the API, e.g. `InReview` → "In review". */
export function statusLabel(status: string): string {
  const words = status.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
