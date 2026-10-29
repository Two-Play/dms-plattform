import type { ChangeKind, StructureChange } from '@/api/versions'
import { t, tOptional } from '@/i18n'

/** Major versions change content; minor ones are editorial corrections. */
export function changeKindLabel(kind: string): string {
  return tOptional(`docPanels.changeKinds.${kind}`) ?? kind
}

/**
 * The labels the next major and minor version get when started from the valid version `label` (e.g. `1.1` →
 * `2.0` and `1.2`), or nothing if `label` is not a released version number.
 */
export function nextLabels(label: string): Record<ChangeKind, string> | undefined {
  const match = /^(\d+)\.(\d+)$/.exec(label)
  if (!match) return undefined
  const major = Number(match[1])
  const minor = Number(match[2])
  return { Major: `${major + 1}.0`, Minor: `${major}.${minor + 1}` }
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
