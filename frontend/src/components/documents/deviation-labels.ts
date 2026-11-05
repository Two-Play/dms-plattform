import type { Deviation, DeviationChange, PathStep } from '@/api/deviations'
import { t, tOptional } from '@/i18n'

/** The element of a locked section in the deviations panel; also the target of links to the section. */
export function lockedSectionId(sectionId: string): string {
  return `locked-section-${sectionId}`
}

/** A change of standard text as it is shown: what happened, and the text before and after it. */
export interface ChangeText {
  label: string
  before?: string
  after?: string
}

function placeholder(p: { path: string; format?: string }): string {
  return p.format ? `{{${p.path}|${p.format}}}` : `{{${p.path}}}`
}

/** The words and placeholders of blocks, however deep they are nested. */
function blockWords(node: unknown): string[] {
  if (Array.isArray(node)) return node.flatMap(blockWords)
  if (typeof node !== 'object' || node === null) return []
  const { type, value, path } = node as Record<string, unknown>
  if (type === 'Text' && typeof value === 'string') return [value]
  if (type === 'Placeholder' && typeof path === 'string') return [`{{${path}}}`]
  return Object.values(node).flatMap(blockWords)
}

function blocksText(blocks: unknown): string | undefined {
  const text = blockWords(blocks).join(' ').replace(/\s+/g, ' ').trim()
  return text === '' ? undefined : text
}

/** The change in the language of the UI; the report itself carries data, not sentences. */
export function changeText(change: DeviationChange): ChangeText {
  const label = tOptional(`deviations.change.${change.type}`) ?? change.type
  switch (change.type) {
    case 'TextInserted':
    case 'FormattingChanged':
      return { label, after: change.text }
    case 'TextDeleted':
      return { label, before: change.text }
    case 'TextReplaced':
    case 'ConditionChanged':
      return { label, before: change.from, after: change.to }
    case 'PlaceholderInserted':
      return { label, after: placeholder(change.placeholder) }
    case 'PlaceholderRemoved':
      return { label, before: placeholder(change.placeholder) }
    case 'PlaceholderChanged':
      return { label, before: placeholder(change.from), after: placeholder(change.to) }
    case 'RepeatChanged':
      return {
        label,
        before: `${change.fromItem} ∈ ${change.fromCollection}`,
        after: `${change.toItem} ∈ ${change.toCollection}`,
      }
    case 'ClauseChanged':
      return {
        label,
        before: `${change.fromKey} v${String(change.fromVersion)}`,
        after: `${change.toKey} v${String(change.toVersion)}`,
      }
    case 'HeadingLevelChanged':
    case 'HeaderRowsChanged':
      return { label, before: String(change.from), after: String(change.to) }
    case 'BlocksInserted':
      return { label, after: blocksText(change.blocks) }
    case 'BlocksDeleted':
      return { label, before: blocksText(change.blocks) }
    case 'SectionRenamed':
      return { label, before: change.from, after: change.to }
    default:
      return { label }
  }
}

/** Positions in the report count from zero; people count from one. */
function stepText(step: PathStep): string {
  switch (step.type) {
    case 'Block':
      return t('deviations.path.block', { n: step.index + 1 })
    case 'Item':
      return t('deviations.path.item', { n: step.index + 1 })
    case 'Row':
      return t('deviations.path.row', { n: step.index + 1 })
    case 'Cell':
      return t('deviations.path.cell', { row: step.row + 1, column: step.column + 1 })
    case 'Then':
      return t('deviations.path.then')
    case 'Otherwise':
      return t('deviations.path.otherwise')
    default:
      return t('deviations.path.body')
  }
}

/** Where a deviation is, e.g. "Liability › Block 3 › Word 6", with the title of the section. */
export function locationText(deviation: Deviation, sectionTitle: string): string {
  const steps = (deviation.path ?? []).map(stepText)
  const word =
    deviation.token === undefined ? [] : [t('deviations.path.word', { n: deviation.token + 1 })]
  return [sectionTitle, ...steps, ...word].join(' › ')
}
