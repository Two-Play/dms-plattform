/**
 * Accepts only same-origin absolute paths as post-sign-in target, so a crafted
 * `?redirect=` cannot send the user to another site.
 */
export function safeReturnTo(value: unknown): string {
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  return '/'
}
