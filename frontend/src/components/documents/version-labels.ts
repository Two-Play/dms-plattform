import type { StructureChange } from '@/api/versions'
import { t, tOptional } from '@/i18n'

/** Major versions change content; minor ones are editorial corrections. */
export function changeKindLabel(kind: string): string {
  return tOptional(`docPanels.changeKinds.${kind}`) ?? kind
}

/** A structural change in words, e.g. "Heading level 2 → 3". */
export function describeChange(change: StructureChange): string {
  const fromTo = `${change.from ?? ''} → ${change.to ?? ''}`
  switch (change.kind) {
    case 'HeadingLevel':
      return t('docPanels.structure.HeadingLevel', { change: fromTo })
    case 'Formatting':
      return t('docPanels.structure.Formatting')
    case 'Condition':
      return t('docPanels.structure.Condition')
    case 'Repeat':
      return t('docPanels.structure.Repeat')
    case 'Clause':
      return t('docPanels.structure.Clause', { change: fromTo })
    case 'HeaderRows':
      return t('docPanels.structure.HeaderRows', { change: fromTo })
    default:
      return change.kind
  }
}
