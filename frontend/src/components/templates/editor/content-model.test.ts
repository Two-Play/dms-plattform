import { describe, expect, it } from 'vitest'
import type { ContentIssue, TemplateContent } from '@/api/template-admin'
import {
  addTableColumn,
  addTableRow,
  canonical,
  contentKey,
  duplicateSectionIds,
  fromEditor,
  move,
  newBlock,
  newSection,
  parseContent,
  placeIssues,
  REFERENCES_TARGET,
  removeTableColumn,
  removeTableRow,
  toEditor,
  UNPLACED_TARGET,
  withTextStyle,
  type Block,
  type EditorBlock,
} from './content-model'

const paragraph = (value: string): Block => ({
  type: 'Paragraph',
  content: [{ type: 'Text', value, marks: [] }],
})

/** Content as the server sends it: every block and inline type, nested, with all defaults written. */
const full: TemplateContent = {
  sections: [
    {
      id: 'intro',
      title: 'Contract',
      locked: false,
      blocks: [
        {
          type: 'Heading',
          level: 1,
          content: [
            { type: 'Text', value: 'Service contract ', marks: ['Bold'] },
            { type: 'Placeholder', path: 'contractNumber' },
          ],
        },
        {
          type: 'Paragraph',
          content: [
            { type: 'Text', value: 'Between ', marks: [] },
            { type: 'Placeholder', path: 'partnerName', format: 'plain' },
            { type: 'LineBreak' },
            { type: 'Text', value: 'and us', marks: ['Bold', 'Italic', 'Underline'] },
          ],
        },
        { type: 'BulletList', items: [[paragraph('one')], [paragraph('two'), paragraph('2b')]] },
        { type: 'OrderedList', items: [[paragraph('first')]] },
        {
          type: 'Table',
          rows: [
            [[paragraph('Service')], [paragraph('Price')]],
            [
              [paragraph('Support')],
              [
                {
                  type: 'Paragraph',
                  content: [{ type: 'Placeholder', path: 'fee', format: 'amount' }],
                },
              ],
            ],
          ],
          headerRows: 1,
        },
        { type: 'PageBreak' },
      ],
    },
    {
      id: 'terms',
      locked: true,
      blocks: [
        { type: 'ClauseRef', clauseKey: 'liability', version: 3 },
        {
          type: 'Conditional',
          condition: 'termType == "fixed"',
          body: [paragraph('Fixed term')],
          otherwise: [paragraph('Open-ended')],
        },
        {
          type: 'Repeat',
          collection: 'services',
          itemName: 'service',
          body: [
            {
              type: 'Paragraph',
              content: [{ type: 'Placeholder', path: 'service' }],
            },
          ],
        },
      ],
    },
  ],
  computed: [{ key: 'total', expression: 'fee.amount * 12.0' }],
  references: [
    {
      key: 'partner',
      label: 'Contract partner',
      source: 'partner-master',
      fields: [
        { attribute: 'id', field: 'partnerId' },
        { attribute: 'name', field: 'partnerName' },
      ],
    },
  ],
}

describe('editor state and content model', () => {
  it('round-trips every block and inline type unchanged', () => {
    expect(fromEditor(toEditor(full))).toEqual(full)
    expect(JSON.stringify(fromEditor(toEditor(full)))).toBe(JSON.stringify(full))
  })

  it.each(full.sections?.flatMap((s) => s.blocks ?? []) ?? [])(
    'round-trips a single $type block',
    (block) => {
      const content: TemplateContent = {
        sections: [{ id: 's', locked: false, blocks: [block] }],
        computed: [],
        references: [],
      }
      expect(fromEditor(toEditor(content))).toEqual(content)
    },
  )

  it('keeps unknown top-level properties and an empty title', () => {
    const content = {
      format: 1,
      sections: [{ id: 'a', title: '', locked: false, blocks: [] }],
      computed: [],
      references: [],
    } as unknown as TemplateContent
    expect(fromEditor(toEditor(content))).toEqual(content)
  })

  it('fills the defaults the server would fill for older or hand-written content', () => {
    const sparse = {
      sections: [
        {
          id: 'a',
          locked: false,
          blocks: [
            { type: 'Paragraph', content: [{ type: 'Text', value: 'x' }] },
            { type: 'Conditional', condition: 'flag' },
          ],
        },
      ],
    } as unknown as TemplateContent
    expect(canonical(sparse)).toEqual({
      sections: [
        {
          id: 'a',
          locked: false,
          blocks: [
            { type: 'Paragraph', content: [{ type: 'Text', value: 'x', marks: [] }] },
            { type: 'Conditional', condition: 'flag', body: [], otherwise: [] },
          ],
        },
      ],
      computed: [],
      references: [],
    })
    expect(canonical(canonical(sparse))).toEqual(canonical(sparse))
  })

  it('gives every node its own uid and ignores uids when comparing content', () => {
    const a = toEditor(full)
    const b = toEditor(full)
    expect(a.sections[0]?.uid).not.toBe(b.sections[0]?.uid)
    expect(contentKey(a)).toBe(contentKey(b))
  })
})

describe('editing operations', () => {
  it('moves, clamping to the list', () => {
    expect(move(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a'])
    expect(move(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
    expect(move(['a', 'b', 'c'], 1, 9)).toEqual(['a', 'c', 'b'])
    expect(move(['a', 'b'], 5, 0)).toEqual(['a', 'b'])
  })

  it('adds sections with free ids and reports duplicates', () => {
    const state = toEditor(full)
    const s1 = newSection(state.sections, 'New')
    const s2 = newSection([...state.sections, s1], 'New')
    expect(s1.id).toBe('section-3')
    expect(s2.id).toBe('section-4')
    expect(s1.blocks.map((b) => b.type)).toEqual(['Paragraph'])
    expect(duplicateSectionIds([...state.sections, { ...s1, id: 'intro' }])).toEqual(
      new Set(['intro']),
    )
  })

  it('creates every block type in a shape the model accepts', () => {
    const blocks: EditorBlock[] = (
      [
        'Paragraph',
        'Heading',
        'BulletList',
        'OrderedList',
        'Table',
        'ClauseRef',
        'Conditional',
        'Repeat',
        'PageBreak',
      ] as const
    ).map(newBlock)
    const content = fromEditor({
      sections: [{ uid: 's', id: 's', locked: false, blocks }],
      computed: [],
      references: [],
      extra: {},
    })
    const parsed = parseContent(JSON.stringify(content))
    expect(parsed.ok).toBe(true)
  })

  it('adds and removes table rows and columns', () => {
    const table = newBlock('Table')
    if (table.type !== 'Table') throw new Error('not a table')
    const wider = addTableColumn(addTableRow(table))
    expect(wider.rows).toHaveLength(3)
    expect(wider.rows.every((r) => r.cells.length === 3)).toBe(true)
    const narrower = removeTableColumn(removeTableRow(wider, 0), 1)
    expect(narrower.rows).toHaveLength(2)
    expect(narrower.rows.every((r) => r.cells.length === 2)).toBe(true)
    const none = removeTableRow(removeTableRow(narrower, 0), 0)
    expect(none.headerRows).toBe(0)
  })

  it('turns paragraphs into headings and back, keeping the text', () => {
    const p = toEditor({ sections: [{ id: 's', locked: false, blocks: [paragraph('Hi')] }] })
      .sections[0]?.blocks[0]
    if (p?.type !== 'Paragraph') throw new Error('not a paragraph')
    const h = withTextStyle(p, 2)
    expect(h).toMatchObject({ type: 'Heading', level: 2, content: p.content, uid: p.uid })
    if (h.type !== 'Heading') throw new Error('not a heading')
    expect(withTextStyle(h, 0)).toMatchObject({ type: 'Paragraph', content: p.content })
  })
})

describe('JSON of the advanced view', () => {
  it('accepts content and reports syntax and shape problems with their path', () => {
    expect(parseContent(JSON.stringify(full))).toEqual({ ok: true, content: full })
    expect('syntax' in parseContent('{"sections": [')).toBe(true)
    expect(parseContent('[]')).toEqual({ ok: false, path: '/', problem: 'object' })
    expect(
      parseContent('{"sections":[{"id":"a","locked":false,"blocks":[{"type":"Box"}]}]}'),
    ).toEqual({ ok: false, path: '/sections/0/blocks/0/type', problem: 'blockType' })
    expect(
      parseContent(
        '{"sections":[{"id":"a","locked":false,"blocks":[{"type":"Paragraph","content":[{"type":"Text","value":"x","marks":["Huge"]}]}]}]}',
      ),
    ).toEqual({ ok: false, path: '/sections/0/blocks/0/content/0/marks/0', problem: 'mark' })
    expect(parseContent('{"sections":[{"id":"a"}]}')).toEqual({
      ok: false,
      path: '/sections/0/locked',
      problem: 'boolean',
    })
  })
})

describe('placing server issues', () => {
  const issue = (sectionId: string | undefined, blockPath: string, part = ''): ContentIssue => ({
    location: { sectionId, blockPath, part, line: 0, column: 0 },
    message: `${sectionId ?? ''}${blockPath}`,
  })

  it('finds the block, computed field, reference field or section an issue names', () => {
    const state = toEditor(full)
    const [intro, terms] = state.sections
    if (!intro || !terms) throw new Error('sections missing')
    const table = intro.blocks[4]
    const conditional = terms.blocks[1]
    if (table?.type !== 'Table' || conditional?.type !== 'Conditional') throw new Error('shape')
    const cell = table.rows[1]?.cells[1]?.blocks[0]
    const otherwise = conditional.otherwise[0]

    const placed = placeIssues(state, [
      issue('intro', '/blocks/1', 'content/1'),
      issue('intro', '/blocks/4/rows/1/1/0', 'content/0'),
      issue('terms', '/blocks/1/otherwise/0'),
      issue('terms', '/blocks/1', 'condition'),
      issue('terms', '', 'id'),
      issue(undefined, '/computed/0', 'expression'),
      issue(undefined, '/references/0', 'key'),
      issue(undefined, '/references', 'fields'),
      issue('intro', '/blocks/2/items/7/0'),
      issue('gone', '/blocks/0'),
    ])

    expect(placed.get(intro.blocks[1]?.uid ?? '')?.map((i) => i.location.part)).toEqual([
      'content/1',
    ])
    expect(placed.get(cell?.uid ?? '')).toHaveLength(1)
    expect(placed.get(otherwise?.uid ?? '')).toHaveLength(1)
    expect(placed.get(conditional.uid)).toHaveLength(1)
    expect(placed.get(terms.uid)).toHaveLength(1)
    expect(placed.get(state.computed[0]?.uid ?? '')).toHaveLength(1)
    expect(placed.get(state.references[0]?.uid ?? '')).toHaveLength(1)
    expect(placed.get(REFERENCES_TARGET)).toHaveLength(1)
    // An unknown nested path falls back to the nearest block: the list.
    expect(placed.get(intro.blocks[2]?.uid ?? '')).toHaveLength(1)
    expect(placed.get(UNPLACED_TARGET)).toHaveLength(1)
  })
})
