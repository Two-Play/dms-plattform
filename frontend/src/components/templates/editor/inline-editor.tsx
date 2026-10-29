import { Bold, Braces, CornerDownLeft, Italic, Underline } from 'lucide-react'
import { baseKeymap, chainCommands, toggleMark } from 'prosemirror-commands'
import { history, redo, undo } from 'prosemirror-history'
import { keymap } from 'prosemirror-keymap'
import type { MarkType } from 'prosemirror-model'
import { EditorState, type Command } from 'prosemirror-state'
import { EditorView } from 'prosemirror-view'
import { useEffect, useId, useRef, useState } from 'react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import type { Inline } from './content-model'
import { useEditorEnv, useScope } from './editor-context'
import { formatsFor, placeholderPaths, type ValueKind } from './expressions'
import { docToInlines, inlineSchema, inlinesToDoc } from './inline-schema'

const marks = inlineSchema.marks

function insertBreak(): Command {
  return (state, dispatch) => {
    dispatch?.(
      state.tr.replaceSelectionWith(inlineSchema.nodes.hard_break.create()).scrollIntoView(),
    )
    return true
  }
}

function activeMarks(state: EditorState): Set<string> {
  const { from, to, empty, $from } = state.selection
  const active = new Set<string>()
  for (const type of Object.values(marks)) {
    const on = empty
      ? Boolean(type.isInSet(state.storedMarks ?? $from.marks()))
      : state.doc.rangeHasMark(from, to, type)
    if (on) active.add(type.name)
  }
  return active
}

const OTHER = '__other__'

/** Picks a field (or another path) and a format for a placeholder. */
function PlaceholderPicker({
  onInsert,
  onCancel,
}: {
  onInsert: (path: string, format: string | undefined) => void
  onCancel: () => void
}) {
  const id = useId()
  const vars = useScope()
  const options = vars.flatMap((v) =>
    placeholderPaths(v).map((p) => ({
      ...p,
      label: p.path === v.path ? v.label : `${v.label} › ${p.path.slice(v.path.length + 1)}`,
      source: v.source,
    })),
  )
  const [choice, setChoice] = useState(options[0]?.path ?? OTHER)
  const [other, setOther] = useState('')
  const [format, setFormat] = useState('')
  const kind: ValueKind = options.find((o) => o.path === choice)?.kind ?? 'Unknown'
  const path = choice === OTHER ? other.trim() : choice
  const select = useRef<HTMLSelectElement>(null)

  useEffect(() => {
    select.current?.focus()
  }, [])

  return (
    <div
      role="group"
      aria-label={t('templateEditor.placeholder.title')}
      className="flex flex-wrap items-end gap-2 rounded-md border bg-muted/40 p-2"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault()
          onCancel()
        }
      }}
    >
      <div className="space-y-1">
        <Label htmlFor={`${id}-field`}>{t('templateEditor.placeholder.field')}</Label>
        <select
          ref={select}
          id={`${id}-field`}
          value={choice}
          onChange={(e) => {
            setChoice(e.target.value)
            setFormat('')
          }}
          className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
        >
          {options.map((o) => (
            <option key={o.path} value={o.path}>
              {o.source === 'field' ? `${o.label} (${o.path})` : o.path}
            </option>
          ))}
          <option value={OTHER}>{t('templateEditor.placeholder.other')}</option>
        </select>
      </div>
      {choice === OTHER && (
        <div className="space-y-1">
          <Label htmlFor={`${id}-path`}>{t('templateEditor.placeholder.path')}</Label>
          <Input
            id={`${id}-path`}
            value={other}
            onChange={(e) => {
              setOther(e.target.value)
            }}
            className="w-48 font-mono"
          />
        </div>
      )}
      <div className="space-y-1">
        <Label htmlFor={`${id}-format`}>{t('templateEditor.placeholder.format')}</Label>
        <select
          id={`${id}-format`}
          value={format}
          onChange={(e) => {
            setFormat(e.target.value)
          }}
          className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
        >
          <option value="">{t('templateEditor.placeholder.defaultFormat')}</option>
          {formatsFor(kind).map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </div>
      <Button
        size="sm"
        disabled={path === ''}
        onClick={() => {
          onInsert(path, format === '' ? undefined : format)
        }}
      >
        {t('templateEditor.placeholder.insert')}
      </Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>
        {t('common.cancel')}
      </Button>
    </div>
  )
}

function MarkButton({
  label,
  active,
  onToggle,
  children,
}: {
  label: string
  active: boolean
  onToggle: () => void
  children: React.ReactNode
}) {
  return (
    <Button
      size="icon-xs"
      variant={active ? 'secondary' : 'ghost'}
      aria-label={label}
      aria-pressed={active}
      title={label}
      // Keep the selection in the text while the button is pressed.
      onMouseDown={(e) => {
        e.preventDefault()
      }}
      onClick={onToggle}
    >
      {children}
    </Button>
  )
}

export interface InlineEditorProps {
  /** DOM id of the editable element, the focus target. */
  id: string
  value: readonly Inline[]
  onChange: (content: Inline[]) => void
  /** Enter splits the text block: the text before the caret stays, the text after goes to a new block. */
  onSplit?: (before: Inline[], after: Inline[]) => void
  label: string
  heading?: boolean
  invalid?: boolean
  describedBy?: string
}

/**
 * Rich text for the inline content of a heading or paragraph: bold, italic, underline, line breaks and
 * placeholders picked from the fields in scope (ProseMirror, ADR 0051). The value is read once; later
 * changes come from the editor itself, so a parent that replaces the content remounts it with a new key.
 */
export function InlineEditor({
  id,
  value,
  onChange,
  onSplit,
  label,
  heading = false,
  invalid = false,
  describedBy,
}: InlineEditorProps) {
  const { readOnly } = useEditorEnv()
  const mount = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const callbacks = useRef({ onChange, onSplit })
  const [initial] = useState(value)
  const [active, setActive] = useState<Set<string>>(() => new Set())
  const [picking, setPicking] = useState(false)

  useEffect(() => {
    callbacks.current = { onChange, onSplit }
  })

  useEffect(() => {
    const host = mount.current
    if (!host) return
    const split: Command = (state, dispatch) => {
      const onSplitNow = callbacks.current.onSplit
      if (!onSplitNow) return false
      const { from, to } = state.selection
      if (dispatch) {
        const before = docToInlines(state.doc.cut(0, from))
        const after = docToInlines(state.doc.cut(to))
        dispatch(state.tr.delete(from, state.doc.content.size).setMeta('split', true))
        onSplitNow(before, after)
      }
      return true
    }
    const toggle = (type: MarkType): Command => toggleMark(type)
    const state = EditorState.create({
      doc: inlinesToDoc(initial),
      plugins: [
        history(),
        keymap({
          'Mod-b': toggle(marks.bold),
          'Mod-i': toggle(marks.italic),
          'Mod-u': toggle(marks.underline),
          'Mod-z': undo,
          'Shift-Mod-z': redo,
          'Mod-y': redo,
          'Shift-Enter': insertBreak(),
          Enter: chainCommands(split, insertBreak()),
        }),
        keymap(baseKeymap),
      ],
    })
    const v: EditorView = new EditorView(host, {
      state,
      editable: () => !readOnly,
      dispatchTransaction(tr) {
        const next = v.state.apply(tr)
        v.updateState(next)
        setActive(activeMarks(next))
        if (tr.docChanged && tr.getMeta('split') !== true) {
          callbacks.current.onChange(docToInlines(next.doc))
        }
      },
    })
    view.current = v
    return () => {
      v.destroy()
      view.current = null
    }
  }, [initial, readOnly])

  useEffect(() => {
    view.current?.setProps({
      attributes: {
        id,
        role: 'textbox',
        'aria-multiline': 'true',
        'aria-label': label,
        ...(readOnly ? { 'aria-readonly': 'true' } : {}),
        ...(invalid ? { 'aria-invalid': 'true' } : {}),
        ...(describedBy ? { 'aria-describedby': describedBy } : {}),
        class: cn(
          'min-h-8 rounded-md border border-input bg-background px-2 py-1.5 whitespace-pre-wrap break-words outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive',
          heading ? 'text-base font-semibold' : 'text-sm',
          readOnly && 'border-dashed bg-muted/40',
        ),
      },
    })
  }, [id, label, invalid, describedBy, heading, readOnly])

  function run(command: Command) {
    const v = view.current
    if (!v) return
    command(v.state, v.dispatch)
    v.focus()
  }

  function insertPlaceholder(path: string, format: string | undefined) {
    setPicking(false)
    run((state, dispatch) => {
      const node = inlineSchema.nodes.placeholder.create({ path, format: format ?? null })
      dispatch?.(state.tr.replaceSelectionWith(node, false))
      return true
    })
  }

  return (
    <div className="space-y-1">
      {!readOnly && (
        <div
          role="toolbar"
          aria-label={t('templateEditor.text.toolbar', { label })}
          className="flex flex-wrap items-center gap-0.5"
        >
          <MarkButton
            label={t('templateEditor.text.bold')}
            active={active.has('bold')}
            onToggle={() => {
              run(toggleMark(marks.bold))
            }}
          >
            <Bold aria-hidden="true" />
          </MarkButton>
          <MarkButton
            label={t('templateEditor.text.italic')}
            active={active.has('italic')}
            onToggle={() => {
              run(toggleMark(marks.italic))
            }}
          >
            <Italic aria-hidden="true" />
          </MarkButton>
          <MarkButton
            label={t('templateEditor.text.underline')}
            active={active.has('underline')}
            onToggle={() => {
              run(toggleMark(marks.underline))
            }}
          >
            <Underline aria-hidden="true" />
          </MarkButton>
          <Button
            size="icon-xs"
            variant="ghost"
            aria-label={t('templateEditor.text.lineBreak')}
            title={t('templateEditor.text.lineBreak')}
            onMouseDown={(e) => {
              e.preventDefault()
            }}
            onClick={() => {
              run(insertBreak())
            }}
          >
            <CornerDownLeft aria-hidden="true" />
          </Button>
          <Button
            size="xs"
            variant="ghost"
            aria-expanded={picking}
            onMouseDown={(e) => {
              e.preventDefault()
            }}
            onClick={() => {
              setPicking((p) => !p)
            }}
          >
            <Braces aria-hidden="true" />
            {t('templateEditor.text.insertField')}
          </Button>
        </div>
      )}
      {picking && (
        <PlaceholderPicker
          onInsert={insertPlaceholder}
          onCancel={() => {
            setPicking(false)
            view.current?.focus()
          }}
        />
      )}
      <div ref={mount} />
    </div>
  )
}
