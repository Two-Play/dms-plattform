import { Plus, Trash2 } from 'lucide-react'
import { useId, useState } from 'react'
import { cn } from 'cn'
import type { ContentIssue } from '@/api/template-admin'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import {
  addTableColumn,
  addTableRow,
  blockTypes,
  insertAt,
  move,
  newBlock,
  newCell,
  removeAt,
  removeTableColumn,
  removeTableRow,
  replaceAt,
  withTextStyle,
  newUid,
  type BlockType,
  type EditorBlock,
  type Inline,
  type EditorBlocks,
} from './content-model'
import {
  focusTargetOf,
  itemVariable,
  nodeId,
  ScopeContext,
  selectClass,
  useEditorEnv,
  useScope,
} from './editor-context'
import { CollectionEditor, ConditionEditor } from './expression-editors'
import { isIdentifier } from './expressions'
import { InlineEditor } from './inline-editor'
import { inlineText } from './inline-schema'
import { ReorderControls } from './reorder'
import { useDragReorder } from './use-drag-reorder'

function blockName(block: EditorBlock): string {
  const type = t(`templateEditor.blockTypes.${block.type}`)
  if (block.type === 'Heading' || block.type === 'Paragraph') {
    const text = inlineText(block.content).trim()
    if (text !== '') return `${type} „${text.length > 30 ? `${text.slice(0, 30)}…` : text}“`
  }
  return type
}

function partLabel(part: string): string {
  const content = /^content\/(\d+)$/.exec(part)
  if (content) return t('templateEditor.issues.inline', { n: String(Number(content[1]) + 1) })
  switch (part) {
    case 'condition':
    case 'collection':
    case 'itemName':
    case 'clauseKey':
    case 'version':
    case 'headerRows':
    case 'level':
    case 'block':
    case 'expression':
    case 'key':
    case 'fields':
    case 'id':
      return t(`templateEditor.issues.parts.${part}`)
    default:
      return part
  }
}

/** Server issues shown at the element they belong to. */
export function IssueList({
  id,
  issues,
}: {
  id: string
  issues: readonly ContentIssue[] | undefined
}) {
  if (!issues || issues.length === 0) return null
  return (
    <ul id={id} className="space-y-0.5 text-xs text-destructive">
      {issues.map((issue, i) => (
        <li key={i}>
          {issue.location.part !== '' && (
            <span className="font-medium">{partLabel(issue.location.part)}</span>
          )}
          {issue.location.line > 0 &&
            ` ${t('templateEditor.issues.position', {
              line: String(issue.location.line),
              column: String(issue.location.column),
            })}`}
          {issue.location.part !== '' || issue.location.line > 0 ? ': ' : ''}
          {issue.message}
        </li>
      ))}
    </ul>
  )
}

function hasPart(issues: readonly ContentIssue[] | undefined, part: string): boolean {
  return (issues ?? []).some((i) => i.location.part === part)
}

/** Adds a block of a chosen type at the end of a list. */
function AddBlock({ id, onAdd }: { id: string; onAdd: (type: BlockType) => void }) {
  const [type, setType] = useState<BlockType>('Paragraph')
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Label htmlFor={id} className="sr-only">
        {t('templateEditor.block.type')}
      </Label>
      <select
        id={id}
        value={type}
        onChange={(e) => {
          setType(e.target.value as BlockType)
        }}
        className={selectClass}
      >
        {blockTypes.map((b) => (
          <option key={b} value={b}>
            {t(`templateEditor.blockTypes.${b}`)}
          </option>
        ))}
      </select>
      <Button
        size="sm"
        variant="outline"
        onClick={() => {
          onAdd(type)
        }}
      >
        <Plus aria-hidden="true" />
        {t('templateEditor.block.add')}
      </Button>
    </div>
  )
}

/**
 * The blocks of a section, list item, table cell, branch or repeat: each block with its own editor, move
 * and remove controls, and a control to add blocks at the end.
 */
export function BlockList({
  blocks,
  onChange,
  label,
  compact = false,
}: {
  blocks: readonly EditorBlock[]
  onChange: (blocks: EditorBlock[]) => void
  /** What the list holds, e.g. "Blocks of section Contract". */
  label: string
  compact?: boolean
}) {
  const env = useEditorEnv()
  const addId = useId()
  const reorder = useDragReorder((from, to) => {
    moveBlock(from, to)
  })

  function moveBlock(from: number, to: number, control?: string) {
    const block = blocks[from]
    if (!block) return
    onChange(move(blocks, from, to))
    env.announce(
      t('templateEditor.move.moved', {
        name: blockName(block),
        position: String(to + 1),
        count: String(blocks.length),
      }),
    )
    if (control) env.focus(control)
  }

  function add(type: BlockType) {
    const block = newBlock(type)
    onChange([...blocks, block])
    env.focus(focusTargetOf(block))
  }

  function remove(index: number) {
    const block = blocks[index]
    if (!block) return
    onChange(removeAt(blocks, index))
    env.announce(t('templateEditor.block.removed', { name: blockName(block) }))
    const neighbour = blocks[index + 1] ?? blocks[index - 1]
    env.focus(neighbour ? nodeId(neighbour.uid) : addId)
  }

  return (
    <div className="space-y-2">
      {blocks.length > 0 ? (
        <ol aria-label={label} className={cn('space-y-2', compact && 'space-y-1')}>
          {blocks.map((block, i) => (
            <li
              key={block.uid}
              {...reorder.itemProps(i)}
              className={cn(reorder.over === i && 'rounded-md ring-2 ring-primary')}
            >
              <BlockFrame
                block={block}
                index={i}
                count={blocks.length}
                handleProps={reorder.handleProps(i)}
                onChange={(b) => {
                  onChange(replaceAt(blocks, i, b))
                }}
                onMove={(to, control) => {
                  moveBlock(i, to, control)
                }}
                onRemove={() => {
                  remove(i)
                }}
                onSplit={(before, after) => {
                  if (block.type !== 'Heading' && block.type !== 'Paragraph') return
                  const next: EditorBlock = { uid: newUid(), type: 'Paragraph', content: after }
                  onChange(
                    insertAt(replaceAt(blocks, i, { ...block, content: before }), i + 1, next),
                  )
                  env.focus(focusTargetOf(next))
                }}
              />
            </li>
          ))}
        </ol>
      ) : (
        <p className="text-xs text-muted-foreground">{t('templateEditor.block.empty')}</p>
      )}
      {!env.readOnly && <AddBlock id={addId} onAdd={add} />}
    </div>
  )
}

interface FrameProps {
  block: EditorBlock
  index: number
  count: number
  handleProps: ReturnType<ReturnType<typeof useDragReorder>['handleProps']>
  onChange: (block: EditorBlock) => void
  onMove: (to: number, control: string) => void
  onRemove: () => void
  onSplit: (before: Inline[], after: Inline[]) => void
}

function BlockFrame({
  block,
  index,
  count,
  handleProps,
  onChange,
  onMove,
  onRemove,
  onSplit,
}: FrameProps) {
  const env = useEditorEnv()
  const id = nodeId(block.uid)
  const issues = env.issues.get(block.uid)
  const name = blockName(block)
  const isText = block.type === 'Heading' || block.type === 'Paragraph'

  return (
    <div
      id={id}
      tabIndex={-1}
      role="group"
      aria-labelledby={`${id}-title`}
      aria-describedby={issues ? `${id}-issues` : undefined}
      className={cn(
        'space-y-2 rounded-lg border bg-card p-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        issues && 'border-destructive/60',
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        <ReorderControls
          id={id}
          name={name}
          index={index}
          count={count}
          onMove={onMove}
          handleProps={handleProps}
        />
        <span id={`${id}-title`} className="text-xs font-medium text-muted-foreground">
          {t(`templateEditor.blockTypes.${block.type}`)}
          {block.type === 'Heading' && ` ${String(block.level)}`}
        </span>
        {isText && !env.readOnly && (
          <>
            <Label htmlFor={`${id}-style`} className="sr-only">
              {t('templateEditor.block.style')}
            </Label>
            <select
              id={`${id}-style`}
              value={block.type === 'Heading' ? block.level : 0}
              aria-invalid={hasPart(issues, 'level') ? true : undefined}
              onChange={(e) => {
                onChange(withTextStyle(block, Number(e.target.value)))
              }}
              className={cn(selectClass, 'h-7 text-xs')}
            >
              <option value={0}>{t('templateEditor.blockTypes.Paragraph')}</option>
              {[1, 2, 3, 4, 5, 6].map((l) => (
                <option key={l} value={l}>
                  {t('templateEditor.block.headingLevel', { level: String(l) })}
                </option>
              ))}
            </select>
          </>
        )}
        {!env.readOnly && (
          <Button
            size="icon-xs"
            variant="ghost"
            className="ml-auto"
            aria-label={t('templateEditor.block.remove', { name })}
            onClick={onRemove}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        )}
      </div>
      <BlockBody block={block} onChange={onChange} onSplit={onSplit} />
      <IssueList id={`${id}-issues`} issues={issues} />
    </div>
  )
}

function BlockBody({
  block,
  onChange,
  onSplit,
}: {
  block: EditorBlock
  onChange: (block: EditorBlock) => void
  onSplit: FrameProps['onSplit']
}) {
  const env = useEditorEnv()
  const vars = useScope()
  const id = nodeId(block.uid)
  const issues = env.issues.get(block.uid)

  switch (block.type) {
    case 'Heading':
    case 'Paragraph':
      return (
        <InlineEditor
          id={`${id}-text`}
          value={block.content}
          heading={block.type === 'Heading'}
          label={
            block.type === 'Heading'
              ? t('templateEditor.block.headingText', { level: String(block.level) })
              : t('templateEditor.block.paragraphText')
          }
          invalid={(issues ?? []).some((i) => i.location.part.startsWith('content/'))}
          describedBy={issues ? `${id}-issues` : undefined}
          onChange={(content) => {
            onChange({ ...block, content })
          }}
          onSplit={onSplit}
        />
      )
    case 'BulletList':
    case 'OrderedList':
      return <ListEditor block={block} onChange={onChange} />
    case 'Table':
      return <TableEditor block={block} onChange={onChange} />
    case 'ClauseRef':
      return (
        <div className="flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <Label htmlFor={`${id}-key`}>{t('templateEditor.clause.key')}</Label>
            <Input
              id={`${id}-key`}
              value={block.clauseKey}
              readOnly={env.readOnly}
              aria-invalid={
                hasPart(issues, 'clauseKey') || block.clauseKey.trim() === '' ? true : undefined
              }
              onChange={(e) => {
                onChange({ ...block, clauseKey: e.target.value })
              }}
              className="w-56 font-mono"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor={`${id}-version`}>{t('templateEditor.clause.version')}</Label>
            <Input
              id={`${id}-version`}
              type="number"
              min={1}
              step={1}
              value={block.version}
              readOnly={env.readOnly}
              aria-invalid={hasPart(issues, 'version') || block.version < 1 ? true : undefined}
              onChange={(e) => {
                onChange({ ...block, version: Math.trunc(Number(e.target.value)) })
              }}
              className="w-24"
            />
          </div>
          <p className="w-full text-xs text-muted-foreground">{t('templateEditor.clause.hint')}</p>
        </div>
      )
    case 'Conditional':
      return (
        <div className="space-y-3">
          <ConditionEditor
            id={`${id}-condition`}
            value={block.condition}
            invalid={hasPart(issues, 'condition')}
            onChange={(condition) => {
              onChange({ ...block, condition })
            }}
          />
          <Branch label={t('templateEditor.conditional.then')}>
            <BlockList
              blocks={block.body}
              label={t('templateEditor.conditional.then')}
              compact
              onChange={(body) => {
                onChange({ ...block, body })
              }}
            />
          </Branch>
          <Branch label={t('templateEditor.conditional.otherwise')}>
            <BlockList
              blocks={block.otherwise}
              label={t('templateEditor.conditional.otherwise')}
              compact
              onChange={(otherwise) => {
                onChange({ ...block, otherwise })
              }}
            />
          </Branch>
        </div>
      )
    case 'Repeat': {
      const itemOk = isIdentifier(block.itemName)
      const inner = [
        ...vars.filter((v) => v.path !== block.itemName),
        itemVariable(vars, block.collection, block.itemName),
      ]
      return (
        <div className="space-y-3">
          <div className="flex flex-wrap items-start gap-3">
            <CollectionEditor
              id={`${id}-collection`}
              value={block.collection}
              invalid={hasPart(issues, 'collection')}
              onChange={(collection) => {
                onChange({ ...block, collection })
              }}
            />
            <div className="space-y-1">
              <Label htmlFor={`${id}-item`}>{t('templateEditor.repeat.itemName')}</Label>
              <Input
                id={`${id}-item`}
                value={block.itemName}
                readOnly={env.readOnly}
                aria-invalid={!itemOk || hasPart(issues, 'itemName') ? true : undefined}
                aria-describedby={`${id}-item-help`}
                onChange={(e) => {
                  onChange({ ...block, itemName: e.target.value })
                }}
                className="w-40 font-mono"
              />
              <p
                id={`${id}-item-help`}
                className={cn('text-xs', itemOk ? 'text-muted-foreground' : 'text-destructive')}
              >
                {itemOk
                  ? t('templateEditor.repeat.itemHint', { name: block.itemName })
                  : t('templateEditor.identifier')}
              </p>
            </div>
          </div>
          <Branch label={t('templateEditor.repeat.body')}>
            <ScopeContext value={itemOk ? inner : vars}>
              <BlockList
                blocks={block.body}
                label={t('templateEditor.repeat.body')}
                compact
                onChange={(body) => {
                  onChange({ ...block, body })
                }}
              />
            </ScopeContext>
          </Branch>
        </div>
      )
    }
    case 'PageBreak':
      return (
        <p className="border-t border-dashed pt-1 text-center text-xs text-muted-foreground">
          {t('templateEditor.block.pageBreakHint')}
        </p>
      )
  }
}

function Branch({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <fieldset className="space-y-2 rounded-md border-l-4 border-l-primary/30 pl-3">
      <legend className="text-xs font-medium">{label}</legend>
      {children}
    </fieldset>
  )
}

function ListEditor({
  block,
  onChange,
}: {
  block: Extract<EditorBlock, { type: 'BulletList' | 'OrderedList' }>
  onChange: (block: EditorBlock) => void
}) {
  const env = useEditorEnv()
  const id = nodeId(block.uid)
  const items = block.items
  const setItems = (next: EditorBlocks[]) => {
    onChange({ ...block, items: next })
  }
  const reorder = useDragReorder((from, to) => {
    setItems(move(items, from, to))
  })
  const ListTag = block.type === 'OrderedList' ? 'ol' : 'ul'

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        {!env.readOnly && (
          <>
            <Label htmlFor={`${id}-kind`} className="text-xs">
              {t('templateEditor.list.kind')}
            </Label>
            <select
              id={`${id}-kind`}
              value={block.type}
              onChange={(e) => {
                onChange({
                  ...block,
                  type: e.target.value === 'OrderedList' ? 'OrderedList' : 'BulletList',
                })
              }}
              className={cn(selectClass, 'h-7 text-xs')}
            >
              <option value="BulletList">{t('templateEditor.blockTypes.BulletList')}</option>
              <option value="OrderedList">{t('templateEditor.blockTypes.OrderedList')}</option>
            </select>
          </>
        )}
      </div>
      <ListTag
        aria-label={t('templateEditor.list.items')}
        className={cn(
          'space-y-2 pl-5',
          block.type === 'OrderedList' ? 'list-decimal' : 'list-disc',
        )}
      >
        {items.map((item, i) => {
          const name = t('templateEditor.list.item', { n: String(i + 1) })
          const itemId = nodeId(item.uid)
          return (
            <li
              key={item.uid}
              id={itemId}
              tabIndex={-1}
              {...reorder.itemProps(i)}
              className={cn('space-y-1', reorder.over === i && 'rounded-md ring-2 ring-primary')}
            >
              <div className="flex items-center gap-1">
                <span className="text-xs text-muted-foreground">{name}</span>
                <ReorderControls
                  id={itemId}
                  name={name}
                  index={i}
                  count={items.length}
                  handleProps={reorder.handleProps(i)}
                  onMove={(to, control) => {
                    setItems(move(items, i, to))
                    env.announce(
                      t('templateEditor.move.moved', {
                        name,
                        position: String(to + 1),
                        count: String(items.length),
                      }),
                    )
                    env.focus(control)
                  }}
                />
                {!env.readOnly && (
                  <Button
                    size="icon-xs"
                    variant="ghost"
                    aria-label={t('templateEditor.list.remove', { n: String(i + 1) })}
                    onClick={() => {
                      setItems(removeAt(items, i))
                      env.announce(t('templateEditor.block.removed', { name }))
                      env.focus(`${id}-add-item`)
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                )}
              </div>
              <BlockList
                blocks={item.blocks}
                label={name}
                compact
                onChange={(blocks) => {
                  setItems(replaceAt(items, i, { ...item, blocks }))
                }}
              />
            </li>
          )
        })}
      </ListTag>
      {!env.readOnly && (
        <Button
          id={`${id}-add-item`}
          size="xs"
          variant="outline"
          onClick={() => {
            const item = newCell()
            setItems([...items, item])
            const first = item.blocks[0]
            if (first) env.focus(focusTargetOf(first))
          }}
        >
          <Plus aria-hidden="true" />
          {t('templateEditor.list.add')}
        </Button>
      )}
    </div>
  )
}

function TableEditor({
  block,
  onChange,
}: {
  block: Extract<EditorBlock, { type: 'Table' }>
  onChange: (block: EditorBlock) => void
}) {
  const env = useEditorEnv()
  const id = nodeId(block.uid)
  const issues = env.issues.get(block.uid)
  const width = Math.max(0, ...block.rows.map((r) => r.cells.length))

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor={`${id}-header`} className="text-xs">
            {t('templateEditor.table.headerRows')}
          </Label>
          <Input
            id={`${id}-header`}
            type="number"
            min={0}
            max={block.rows.length}
            value={block.headerRows}
            readOnly={env.readOnly}
            aria-invalid={hasPart(issues, 'headerRows') ? true : undefined}
            onChange={(e) => {
              onChange({ ...block, headerRows: Math.trunc(Number(e.target.value)) })
            }}
            className="w-20"
          />
        </div>
        {!env.readOnly && (
          <>
            <Button
              size="xs"
              variant="outline"
              onClick={() => {
                onChange(addTableRow(block))
              }}
            >
              <Plus aria-hidden="true" />
              {t('templateEditor.table.addRow')}
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => {
                onChange(addTableColumn(block))
              }}
            >
              <Plus aria-hidden="true" />
              {t('templateEditor.table.addColumn')}
            </Button>
          </>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <caption className="sr-only">{t('templateEditor.table.caption')}</caption>
          {!env.readOnly && width > 0 && (
            <thead>
              <tr>
                {Array.from({ length: width }, (_, c) => (
                  <th key={c} scope="col" className="p-1 text-left">
                    <Button
                      size="xs"
                      variant="ghost"
                      onClick={() => {
                        onChange(removeTableColumn(block, c))
                      }}
                    >
                      <Trash2 aria-hidden="true" />
                      {t('templateEditor.table.removeColumn', { n: String(c + 1) })}
                    </Button>
                  </th>
                ))}
                <th scope="col" className="sr-only">
                  {t('templateEditor.table.rowActions')}
                </th>
              </tr>
            </thead>
          )}
          <tbody>
            {block.rows.map((row, r) => (
              <tr key={row.uid} className={cn(r < block.headerRows && 'bg-muted/50')}>
                {row.cells.map((cell, c) => {
                  const label = t('templateEditor.table.cell', {
                    row: String(r + 1),
                    column: String(c + 1),
                  })
                  return (
                    <td key={cell.uid} className="min-w-48 border p-1 align-top">
                      <BlockList
                        blocks={cell.blocks}
                        label={label}
                        compact
                        onChange={(blocks) => {
                          onChange({
                            ...block,
                            rows: replaceAt(block.rows, r, {
                              ...row,
                              cells: replaceAt(row.cells, c, { ...cell, blocks }),
                            }),
                          })
                        }}
                      />
                    </td>
                  )
                })}
                {!env.readOnly && (
                  <td className="p-1 align-top">
                    <Button
                      size="icon-xs"
                      variant="ghost"
                      aria-label={t('templateEditor.table.removeRow', { n: String(r + 1) })}
                      onClick={() => {
                        onChange(removeTableRow(block, r))
                      }}
                    >
                      <Trash2 aria-hidden="true" />
                    </Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
