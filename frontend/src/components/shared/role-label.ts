import { tOptional } from '@/i18n'

/** A business or workflow role (e.g. `Approver`) in the active language; unknown roles as they are. */
export function roleLabel(role: string): string {
  return tOptional(`roles.${role}`) ?? role
}
