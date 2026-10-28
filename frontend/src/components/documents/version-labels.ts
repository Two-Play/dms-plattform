import type { StructureChange } from '@/api/versions'

/** Major versions change content; minor ones are editorial corrections. */
export function changeKindLabel(kind: string): string {
  return kind === 'Minor' ? 'Editorial' : kind
}

/** A structural change in words, e.g. "Heading level 2 → 3". */
export function describeChange(change: StructureChange): string {
  const fromTo = `${change.from ?? ''} → ${change.to ?? ''}`
  switch (change.kind) {
    case 'HeadingLevel':
      return `Heading level ${fromTo}`
    case 'Formatting':
      return 'Formatting changed'
    case 'Condition':
      return 'Condition changed'
    case 'Repeat':
      return 'Repetition changed'
    case 'Clause':
      return `Clause ${fromTo}`
    case 'HeaderRows':
      return `Header rows ${fromTo}`
    default:
      return change.kind
  }
}
