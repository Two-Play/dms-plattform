import { describe, expect, it } from 'vitest'
import type { Inline } from './content-model'
import { docToInlines, inlineText, inlinesToDoc, normalizeInlines } from './inline-schema'

describe('inline content and the ProseMirror document', () => {
  const every: Inline[] = [
    { type: 'Text', value: 'plain ', marks: [] },
    { type: 'Text', value: 'bold ', marks: ['Bold'] },
    { type: 'Text', value: 'italic ', marks: ['Italic'] },
    { type: 'Text', value: 'underlined ', marks: ['Underline'] },
    { type: 'Text', value: 'all', marks: ['Bold', 'Italic', 'Underline'] },
    { type: 'LineBreak' },
    { type: 'Placeholder', path: 'contractValue' },
    { type: 'Placeholder', path: 'startDate', format: 'date:long' },
    { type: 'Placeholder', path: 'fee.currency', format: 'plain' },
  ]

  it('round-trips every inline type and mark', () => {
    expect(docToInlines(inlinesToDoc(every))).toEqual(every)
  })

  it('maps nodes and marks to the schema', () => {
    const doc = inlinesToDoc(every)
    expect(doc.childCount).toBe(every.length)
    expect(doc.child(1).marks.map((m) => m.type.name)).toEqual(['bold'])
    expect(doc.child(4).marks.map((m) => m.type.name)).toEqual(['bold', 'italic', 'underline'])
    expect(doc.child(5).type.name).toBe('hard_break')
    expect(doc.child(7).attrs).toEqual({ path: 'startDate', format: 'date:long' })
  })

  it('normalizes only what does not change the rendering', () => {
    expect(
      normalizeInlines([
        { type: 'Text', value: 'a', marks: [] },
        { type: 'Text', value: '', marks: ['Bold'] },
        { type: 'Text', value: 'b', marks: [] },
        { type: 'Text', value: 'c', marks: ['Underline', 'Bold'] },
      ]),
    ).toEqual([
      { type: 'Text', value: 'ab', marks: [] },
      { type: 'Text', value: 'c', marks: ['Bold', 'Underline'] },
    ])
    expect(normalizeInlines([])).toEqual([])
  })

  it('reads as plain text with placeholders', () => {
    expect(inlineText(every)).toBe(
      'plain bold italic underlined all {{contractValue}}{{startDate}}{{fee.currency}}',
    )
  })
})
