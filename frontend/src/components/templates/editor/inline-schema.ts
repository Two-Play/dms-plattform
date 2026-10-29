import { Schema, type Mark as PmMark, type Node as PmNode } from 'prosemirror-model'
import type { Inline, Mark } from './content-model'

/**
 * ProseMirror schema for the inline content of a heading or paragraph (DMS-128, ADR 0051): exactly what
 * the content model allows inside a text block, nothing more. The document is one text block of inline
 * nodes, so pasted lists or tables cannot slip in; blocks are edited by the block editor around it.
 */
export const inlineSchema = new Schema({
  nodes: {
    doc: { content: 'inline*' },
    text: { group: 'inline' },
    placeholder: {
      group: 'inline',
      inline: true,
      atom: true,
      selectable: true,
      attrs: { path: { default: '' }, format: { default: null } },
      toDOM: (node) => {
        const path = String(node.attrs.path)
        const format = node.attrs.format as string | null
        return [
          'span',
          {
            class:
              'mx-0.5 rounded bg-primary/10 px-1 font-mono text-xs text-primary ring-1 ring-primary/30',
            'data-path': path,
            ...(format ? { 'data-format': format } : {}),
            contenteditable: 'false',
          },
          format ? `{{${path} | ${format}}}` : `{{${path}}}`,
        ]
      },
      parseDOM: [
        {
          tag: 'span[data-path]',
          getAttrs: (dom) => ({
            path: dom.getAttribute('data-path') ?? '',
            format: dom.getAttribute('data-format'),
          }),
        },
      ],
    },
    hard_break: {
      group: 'inline',
      inline: true,
      selectable: false,
      toDOM: () => ['br'],
      parseDOM: [{ tag: 'br' }],
    },
  },
  marks: {
    bold: {
      toDOM: () => ['strong', 0],
      parseDOM: [{ tag: 'strong' }, { tag: 'b' }, { style: 'font-weight=bold' }],
    },
    italic: {
      toDOM: () => ['em', 0],
      parseDOM: [{ tag: 'em' }, { tag: 'i' }, { style: 'font-style=italic' }],
    },
    underline: {
      toDOM: () => ['u', 0],
      parseDOM: [{ tag: 'u' }, { style: 'text-decoration=underline' }],
    },
  },
})

const toPm: Record<Mark, string> = { Bold: 'bold', Italic: 'italic', Underline: 'underline' }
const fromPm: Record<string, Mark> = { bold: 'Bold', italic: 'Italic', underline: 'Underline' }

/** The ProseMirror document for inline content. */
export function inlinesToDoc(content: readonly Inline[]): PmNode {
  const nodes: PmNode[] = []
  for (const i of content) {
    switch (i.type) {
      case 'Text':
        if (i.value !== '') {
          const marks = (i.marks ?? []).map((m) => inlineSchema.mark(toPm[m]))
          nodes.push(inlineSchema.text(i.value, marks))
        }
        break
      case 'Placeholder':
        nodes.push(
          inlineSchema.nodes.placeholder.create({ path: i.path, format: i.format ?? null }),
        )
        break
      case 'LineBreak':
        nodes.push(inlineSchema.nodes.hard_break.create())
        break
    }
  }
  return inlineSchema.nodes.doc.create(null, nodes)
}

function marksOf(marks: readonly PmMark[]): Mark[] {
  return marks.flatMap((m) => {
    const mark = fromPm[m.type.name]
    return mark ? [mark] : []
  })
}

/** The inline content of a ProseMirror document. */
export function docToInlines(doc: PmNode): Inline[] {
  const result: Inline[] = []
  doc.forEach((node) => {
    if (node.isText) {
      result.push({ type: 'Text', value: node.text ?? '', marks: marksOf(node.marks) })
    } else if (node.type.name === 'placeholder') {
      const format = node.attrs.format as string | null
      const path = String(node.attrs.path)
      result.push(format ? { type: 'Placeholder', path, format } : { type: 'Placeholder', path })
    } else if (node.type.name === 'hard_break') {
      result.push({ type: 'LineBreak' })
    }
  })
  return result
}

/**
 * Inline content as ProseMirror keeps it: adjacent text runs with the same marks merged, empty runs
 * dropped, marks in the order Bold, Italic, Underline. Rendering is the same as for the input; this is
 * the only change an edited text block makes to its stored form.
 */
export function normalizeInlines(content: readonly Inline[]): Inline[] {
  return docToInlines(inlinesToDoc(content))
}

/** The plain text of inline content, placeholders as `{{path}}` (for labels and announcements). */
export function inlineText(content: readonly Inline[]): string {
  return content
    .map((i) => {
      switch (i.type) {
        case 'Text':
          return i.value
        case 'Placeholder':
          return `{{${i.path}}}`
        case 'LineBreak':
          return ' '
      }
    })
    .join('')
}
