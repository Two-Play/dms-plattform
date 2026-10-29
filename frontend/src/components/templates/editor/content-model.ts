import type { components } from '@/api/schema'
import type { ContentIssue, TemplateContent } from '@/api/template-admin'

type Schemas = components['schemas']

/** The stored content model (ADR 0007), as the API sends it. */
export type Block = Schemas['Block']
export type Inline = Schemas['Inline']
export type Mark = Schemas['Mark']
export type Section = Schemas['Section']
export type ComputedField = Schemas['ComputedField']
export type ReferenceField = Schemas['ReferenceField']

/**
 * The editor state of a structured template (DMS-128): the content model with a stable `uid` on every
 * node the editor can move, remove or point a message at. `uid`s live only in the browser; `toEditor`
 * and `fromEditor` map losslessly between this state and the stored model (ADR 0051).
 */
export interface EditorContent {
  sections: EditorSection[]
  computed: EditorComputed[]
  references: EditorReference[]
  /** Top-level properties the editor does not know (e.g. `format`), written back unchanged. */
  extra: Record<string, unknown>
}

export interface EditorSection {
  uid: string
  id: string
  title?: string
  locked: boolean
  blocks: EditorBlock[]
}

export interface EditorComputed {
  uid: string
  key: string
  expression: string
}

export interface EditorMapping {
  uid: string
  attribute: string
  field: string
}

export interface EditorReference {
  uid: string
  key: string
  label: string
  source: string
  fields: EditorMapping[]
}

/** A list item or table cell: a list of blocks of its own. */
export interface EditorBlocks {
  uid: string
  blocks: EditorBlock[]
}

export interface EditorRow {
  uid: string
  cells: EditorBlocks[]
}

export type EditorBlock =
  | { uid: string; type: 'Heading'; level: number; content: Inline[] }
  | { uid: string; type: 'Paragraph'; content: Inline[] }
  | { uid: string; type: 'BulletList' | 'OrderedList'; items: EditorBlocks[] }
  | { uid: string; type: 'Table'; headerRows: number; rows: EditorRow[] }
  | { uid: string; type: 'ClauseRef'; clauseKey: string; version: number }
  | {
      uid: string
      type: 'Conditional'
      condition: string
      body: EditorBlock[]
      otherwise: EditorBlock[]
    }
  | { uid: string; type: 'Repeat'; collection: string; itemName: string; body: EditorBlock[] }
  | { uid: string; type: 'PageBreak' }

export type BlockType = EditorBlock['type']

/** Block types in the order the editor offers them. */
export const blockTypes: readonly BlockType[] = [
  'Paragraph',
  'Heading',
  'BulletList',
  'OrderedList',
  'Table',
  'ClauseRef',
  'Conditional',
  'Repeat',
  'PageBreak',
]

/** Master data sources and their attributes (`ReferenceSource` in the backend, ADR 0023). */
export const referenceSources: Record<string, readonly string[]> = {
  'partner-master': ['id', 'name', 'street', 'postalCode', 'city', 'country', 'address', 'vatId'],
}

let counter = 0

/** A new id for an editor node, unique within the page. */
export function newUid(): string {
  counter += 1
  return `n${String(counter)}`
}

const markOrder: readonly Mark[] = ['Bold', 'Italic', 'Underline']

/** An inline in its canonical form: `marks` always present, as the server writes it. */
function canonicalInline(i: Inline): Inline {
  switch (i.type) {
    case 'Text':
      return { type: 'Text', value: i.value, marks: [...(i.marks ?? [])] }
    case 'Placeholder':
      return i.format === undefined
        ? { type: 'Placeholder', path: i.path }
        : { type: 'Placeholder', path: i.path, format: i.format }
    case 'LineBreak':
      return { type: 'LineBreak' }
  }
}

function blocksToEditor(blocks: Block[] | undefined): EditorBlock[] {
  return (blocks ?? []).map(blockToEditor)
}

function blockToEditor(b: Block): EditorBlock {
  const uid = newUid()
  switch (b.type) {
    case 'Heading':
      return {
        uid,
        type: 'Heading',
        level: b.level,
        content: (b.content ?? []).map(canonicalInline),
      }
    case 'Paragraph':
      return { uid, type: 'Paragraph', content: (b.content ?? []).map(canonicalInline) }
    case 'BulletList':
    case 'OrderedList':
      return {
        uid,
        type: b.type,
        items: (b.items ?? []).map((item) => ({ uid: newUid(), blocks: blocksToEditor(item) })),
      }
    case 'Table':
      return {
        uid,
        type: 'Table',
        headerRows: b.headerRows,
        rows: (b.rows ?? []).map((row) => ({
          uid: newUid(),
          cells: row.map((cell) => ({ uid: newUid(), blocks: blocksToEditor(cell) })),
        })),
      }
    case 'ClauseRef':
      return { uid, type: 'ClauseRef', clauseKey: b.clauseKey, version: b.version }
    case 'Conditional':
      return {
        uid,
        type: 'Conditional',
        condition: b.condition,
        body: blocksToEditor(b.body),
        otherwise: blocksToEditor(b.otherwise),
      }
    case 'Repeat':
      return {
        uid,
        type: 'Repeat',
        collection: b.collection,
        itemName: b.itemName,
        body: blocksToEditor(b.body),
      }
    case 'PageBreak':
      return { uid, type: 'PageBreak' }
  }
}

/** The editor state for stored content. */
export function toEditor(content: TemplateContent): EditorContent {
  const { sections, computed, references, ...extra } = content
  return {
    sections: (sections ?? []).map((s) => {
      const section: EditorSection = {
        uid: newUid(),
        id: s.id,
        locked: s.locked,
        blocks: blocksToEditor(s.blocks),
      }
      if (s.title !== undefined) section.title = s.title
      return section
    }),
    computed: (computed ?? []).map((c) => ({
      uid: newUid(),
      key: c.key,
      expression: c.expression,
    })),
    references: (references ?? []).map((r) => ({
      uid: newUid(),
      key: r.key,
      label: r.label,
      source: r.source,
      fields: (r.fields ?? []).map((m) => ({
        uid: newUid(),
        attribute: m.attribute,
        field: m.field,
      })),
    })),
    extra,
  }
}

function blocksFromEditor(blocks: EditorBlock[]): Block[] {
  return blocks.map(blockFromEditor)
}

function blockFromEditor(b: EditorBlock): Block {
  switch (b.type) {
    case 'Heading':
      return { type: 'Heading', level: b.level, content: b.content }
    case 'Paragraph':
      return { type: 'Paragraph', content: b.content }
    case 'BulletList':
    case 'OrderedList':
      return { type: b.type, items: b.items.map((item) => blocksFromEditor(item.blocks)) }
    case 'Table':
      return {
        type: 'Table',
        rows: b.rows.map((row) => row.cells.map((cell) => blocksFromEditor(cell.blocks))),
        headerRows: b.headerRows,
      }
    case 'ClauseRef':
      return { type: 'ClauseRef', clauseKey: b.clauseKey, version: b.version }
    case 'Conditional':
      return {
        type: 'Conditional',
        condition: b.condition,
        body: blocksFromEditor(b.body),
        otherwise: blocksFromEditor(b.otherwise),
      }
    case 'Repeat':
      return {
        type: 'Repeat',
        collection: b.collection,
        itemName: b.itemName,
        body: blocksFromEditor(b.body),
      }
    case 'PageBreak':
      return { type: 'PageBreak' }
  }
}

/** The stored content for an editor state, in the canonical form the server writes. */
export function fromEditor(content: EditorContent): TemplateContent {
  return {
    ...content.extra,
    sections: content.sections.map((s) => {
      // Same property order as the server writes, so unchanged content serializes identically.
      const section: Section = {
        id: s.id,
        ...(s.title === undefined ? {} : { title: s.title }),
        locked: s.locked,
        blocks: blocksFromEditor(s.blocks),
      }
      return section
    }),
    computed: content.computed.map((c) => ({ key: c.key, expression: c.expression })),
    references: content.references.map((r) => ({
      key: r.key,
      label: r.label,
      source: r.source,
      fields: r.fields.map((m) => ({ attribute: m.attribute, field: m.field })),
    })),
  }
}

/** Stored content with every default filled in, as the server returns it after a save. */
export function canonical(content: TemplateContent): TemplateContent {
  return fromEditor(toEditor(content))
}

/** The content as a stable text, to tell whether the editor has unsaved changes. */
export function contentKey(content: EditorContent): string {
  return JSON.stringify(fromEditor(content))
}

// ---------------------------------------------------------------------------------------------
// Validation of JSON from the advanced view
// ---------------------------------------------------------------------------------------------

/** The content parsed from JSON, or the first structural problem with its JSON path. */
export type ShapeProblem =
  'object' | 'array' | 'string' | 'integer' | 'boolean' | 'mark' | 'inlineType' | 'blockType'

export type ParseResult =
  { ok: true; content: TemplateContent } | { ok: false; path: string; problem: ShapeProblem }

class ShapeError extends Error {
  readonly path: string
  readonly problem: ShapeProblem
  constructor(path: string, problem: ShapeProblem) {
    super(`${path}: ${problem}`)
    this.path = path
    this.problem = problem
  }
}

type Json = Record<string, unknown>

function obj(v: unknown, path: string): Json {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new ShapeError(path, 'object')
  return v as Json
}

function arr(v: unknown, path: string, optional = true): unknown[] {
  if (v === undefined && optional) return []
  if (!Array.isArray(v)) throw new ShapeError(path, 'array')
  return v
}

function str(v: unknown, path: string): string {
  if (typeof v !== 'string') throw new ShapeError(path, 'string')
  return v
}

function int(v: unknown, path: string): number {
  if (typeof v !== 'number' || !Number.isInteger(v)) throw new ShapeError(path, 'integer')
  return v
}

function bool(v: unknown, path: string): boolean {
  if (typeof v !== 'boolean') throw new ShapeError(path, 'boolean')
  return v
}

function checkInline(v: unknown, path: string): void {
  const o = obj(v, path)
  switch (o.type) {
    case 'Text':
      str(o.value, `${path}/value`)
      arr(o.marks, `${path}/marks`).forEach((m, i) => {
        if (!markOrder.includes(m as Mark))
          throw new ShapeError(`${path}/marks/${String(i)}`, 'mark')
      })
      return
    case 'Placeholder':
      str(o.path, `${path}/path`)
      if (o.format !== undefined) str(o.format, `${path}/format`)
      return
    case 'LineBreak':
      return
    default:
      throw new ShapeError(`${path}/type`, 'inlineType')
  }
}

function checkBlocks(v: unknown, path: string): void {
  arr(v, path).forEach((b, i) => {
    checkBlock(b, `${path}/${String(i)}`)
  })
}

function checkBlock(v: unknown, path: string): void {
  const o = obj(v, path)
  switch (o.type) {
    case 'Heading':
      int(o.level, `${path}/level`)
      arr(o.content, `${path}/content`).forEach((x, i) => {
        checkInline(x, `${path}/content/${String(i)}`)
      })
      return
    case 'Paragraph':
      arr(o.content, `${path}/content`).forEach((x, i) => {
        checkInline(x, `${path}/content/${String(i)}`)
      })
      return
    case 'BulletList':
    case 'OrderedList':
      arr(o.items, `${path}/items`).forEach((item, k) => {
        checkBlocks(item, `${path}/items/${String(k)}`)
      })
      return
    case 'Table':
      int(o.headerRows, `${path}/headerRows`)
      arr(o.rows, `${path}/rows`).forEach((row, r) => {
        arr(row, `${path}/rows/${String(r)}`, false).forEach((cell, c) => {
          checkBlocks(cell, `${path}/rows/${String(r)}/${String(c)}`)
        })
      })
      return
    case 'ClauseRef':
      str(o.clauseKey, `${path}/clauseKey`)
      int(o.version, `${path}/version`)
      return
    case 'Conditional':
      str(o.condition, `${path}/condition`)
      checkBlocks(o.body, `${path}/body`)
      checkBlocks(o.otherwise, `${path}/otherwise`)
      return
    case 'Repeat':
      str(o.collection, `${path}/collection`)
      str(o.itemName, `${path}/itemName`)
      checkBlocks(o.body, `${path}/body`)
      return
    case 'PageBreak':
      return
    default:
      throw new ShapeError(`${path}/type`, 'blockType')
  }
}

/**
 * Checks that JSON text has the shape of the content model, so the visual editor can show it. Whether
 * the content compiles against the document class is the server's check.
 */
export function parseContent(text: string): ParseResult | { ok: false; syntax: string } {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch (e) {
    return { ok: false, syntax: e instanceof Error ? e.message : String(e) }
  }
  try {
    const o = obj(value, '')
    arr(o.sections, '/sections', false).forEach((s, i) => {
      const p = `/sections/${String(i)}`
      const section = obj(s, p)
      str(section.id, `${p}/id`)
      if (section.title !== undefined) str(section.title, `${p}/title`)
      bool(section.locked, `${p}/locked`)
      checkBlocks(section.blocks, `${p}/blocks`)
    })
    arr(o.computed, '/computed').forEach((c, i) => {
      const p = `/computed/${String(i)}`
      const field = obj(c, p)
      str(field.key, `${p}/key`)
      str(field.expression, `${p}/expression`)
    })
    arr(o.references, '/references').forEach((r, i) => {
      const p = `/references/${String(i)}`
      const ref = obj(r, p)
      str(ref.key, `${p}/key`)
      str(ref.label, `${p}/label`)
      str(ref.source, `${p}/source`)
      arr(ref.fields, `${p}/fields`).forEach((m, k) => {
        const mapping = obj(m, `${p}/fields/${String(k)}`)
        str(mapping.attribute, `${p}/fields/${String(k)}/attribute`)
        str(mapping.field, `${p}/fields/${String(k)}/field`)
      })
    })
    return { ok: true, content: o }
  } catch (e) {
    if (e instanceof ShapeError) return { ok: false, path: e.path || '/', problem: e.problem }
    throw e
  }
}

// ---------------------------------------------------------------------------------------------
// Editing operations
// ---------------------------------------------------------------------------------------------

/** The list with the element at `from` moved to `to` (both clamped to the list). */
export function move<T>(list: readonly T[], from: number, to: number): T[] {
  const target = Math.max(0, Math.min(list.length - 1, to))
  if (from < 0 || from >= list.length || from === target) return [...list]
  const copy = [...list]
  const [item] = copy.splice(from, 1)
  if (item !== undefined) copy.splice(target, 0, item)
  return copy
}

export function insertAt<T>(list: readonly T[], index: number, item: T): T[] {
  const copy = [...list]
  copy.splice(index, 0, item)
  return copy
}

export function removeAt<T>(list: readonly T[], index: number): T[] {
  return list.filter((_, i) => i !== index)
}

export function replaceAt<T>(list: readonly T[], index: number, item: T): T[] {
  return list.map((x, i) => (i === index ? item : x))
}

/** A section id not used yet: `section-1`, `section-2`, … */
export function freeSectionId(sections: readonly EditorSection[]): string {
  const used = new Set(sections.map((s) => s.id))
  let n = sections.length + 1
  while (used.has(`section-${String(n)}`)) n += 1
  return `section-${String(n)}`
}

export function newSection(sections: readonly EditorSection[], title: string): EditorSection {
  return {
    uid: newUid(),
    id: freeSectionId(sections),
    title,
    locked: false,
    blocks: [newBlock('Paragraph')],
  }
}

function emptyCell(): EditorBlocks {
  return { uid: newUid(), blocks: [newBlock('Paragraph')] }
}

/** A new block of a type, with the content the editor starts from. */
export function newBlock(type: BlockType): EditorBlock {
  const uid = newUid()
  switch (type) {
    case 'Heading':
      return { uid, type, level: 2, content: [] }
    case 'Paragraph':
      return { uid, type, content: [] }
    case 'BulletList':
    case 'OrderedList':
      return { uid, type, items: [emptyCell()] }
    case 'Table':
      return {
        uid,
        type,
        headerRows: 1,
        rows: [0, 1].map(() => ({ uid: newUid(), cells: [emptyCell(), emptyCell()] })),
      }
    case 'ClauseRef':
      return { uid, type, clauseKey: '', version: 1 }
    case 'Conditional':
      return { uid, type, condition: '', body: [newBlock('Paragraph')], otherwise: [] }
    case 'Repeat':
      return { uid, type, collection: '', itemName: 'item', body: [newBlock('Paragraph')] }
    case 'PageBreak':
      return { uid, type }
  }
}

/** A new empty list item or table cell. */
export function newCell(): EditorBlocks {
  return emptyCell()
}

type TableBlock = Extract<EditorBlock, { type: 'Table' }>

function columns(table: TableBlock): number {
  return Math.max(0, ...table.rows.map((r) => r.cells.length))
}

export function addTableRow(table: TableBlock, index = table.rows.length): TableBlock {
  const width = Math.max(1, columns(table))
  const row: EditorRow = { uid: newUid(), cells: Array.from({ length: width }, emptyCell) }
  return { ...table, rows: insertAt(table.rows, index, row) }
}

export function removeTableRow(table: TableBlock, index: number): TableBlock {
  const rows = removeAt(table.rows, index)
  return { ...table, rows, headerRows: Math.min(table.headerRows, rows.length) }
}

export function addTableColumn(table: TableBlock, index = columns(table)): TableBlock {
  return {
    ...table,
    rows: table.rows.map((r) => ({ ...r, cells: insertAt(r.cells, index, emptyCell()) })),
  }
}

export function removeTableColumn(table: TableBlock, index: number): TableBlock {
  return { ...table, rows: table.rows.map((r) => ({ ...r, cells: removeAt(r.cells, index) })) }
}

/** A text block turned into a heading of `level` (0 = paragraph), keeping its inline content. */
export function withTextStyle(
  block: Extract<EditorBlock, { type: 'Heading' | 'Paragraph' }>,
  level: number,
): EditorBlock {
  return level === 0
    ? { uid: block.uid, type: 'Paragraph', content: block.content }
    : { uid: block.uid, type: 'Heading', level, content: block.content }
}

// ---------------------------------------------------------------------------------------------
// Server issues at the affected element
// ---------------------------------------------------------------------------------------------

/** Target for issues about the reference fields as a whole (`/references`). */
export const REFERENCES_TARGET = 'references'
/** Target for issues the editor cannot place. */
export const UNPLACED_TARGET = 'unplaced'

/**
 * The editor node each location of a content issue names: `sectionId|blockPath`, e.g.
 * `main|/blocks/1/body/0` → uid of that block, `|/computed/0` → uid of the computed field.
 */
export function issueTargets(content: EditorContent): Map<string, string> {
  const targets = new Map<string, string>()
  const walk = (blocks: EditorBlock[], section: string, path: string) => {
    blocks.forEach((b, i) => {
      const here = `${path}/${String(i)}`
      targets.set(`${section}|${here}`, b.uid)
      switch (b.type) {
        case 'BulletList':
        case 'OrderedList':
          b.items.forEach((item, k) => {
            walk(item.blocks, section, `${here}/items/${String(k)}`)
          })
          break
        case 'Table':
          b.rows.forEach((row, r) => {
            row.cells.forEach((cell, c) => {
              walk(cell.blocks, section, `${here}/rows/${String(r)}/${String(c)}`)
            })
          })
          break
        case 'Conditional':
          walk(b.body, section, `${here}/body`)
          walk(b.otherwise, section, `${here}/otherwise`)
          break
        case 'Repeat':
          walk(b.body, section, `${here}/body`)
          break
        default:
          break
      }
    })
  }
  content.sections.forEach((s) => {
    if (!targets.has(`${s.id}|`)) targets.set(`${s.id}|`, s.uid)
    walk(s.blocks, s.id, '/blocks')
  })
  content.computed.forEach((c, i) => targets.set(`|/computed/${String(i)}`, c.uid))
  content.references.forEach((r, i) => targets.set(`|/references/${String(i)}`, r.uid))
  targets.set('|/references', REFERENCES_TARGET)
  return targets
}

/**
 * Issues grouped by the uid of the element they belong to. A path the editor does not know falls back
 * to its nearest known ancestor, then to the section, then to `UNPLACED_TARGET`.
 */
export function placeIssues(
  content: EditorContent,
  issues: readonly ContentIssue[],
): Map<string, ContentIssue[]> {
  const targets = issueTargets(content)
  const placed = new Map<string, ContentIssue[]>()
  for (const issue of issues) {
    const section = issue.location.sectionId ?? ''
    let path = issue.location.blockPath
    let uid = targets.get(`${section}|${path}`)
    while (uid === undefined && path.includes('/')) {
      path = path.slice(0, path.lastIndexOf('/'))
      uid = targets.get(`${section}|${path}`)
    }
    uid ??= targets.get(`${section}|`) ?? UNPLACED_TARGET
    placed.set(uid, [...(placed.get(uid) ?? []), issue])
  }
  return placed
}

/** Ids of sections that occur more than once (the server refuses them). */
export function duplicateSectionIds(sections: readonly EditorSection[]): Set<string> {
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const s of sections) {
    if (seen.has(s.id)) duplicates.add(s.id)
    seen.add(s.id)
  }
  return duplicates
}
