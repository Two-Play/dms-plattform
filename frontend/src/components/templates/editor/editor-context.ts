import { createContext, useContext } from 'react'
import type { ContentIssue } from '@/api/template-admin'
import type { TemplateFields } from '@/api/templates'
import type { EditorBlock } from './content-model'
import type { ScopeVariable } from './expressions'

/** What every part of the visual editor needs from the editor around it (DMS-128). */
export interface EditorEnv {
  readOnly: boolean
  /** Server issues by the uid of the element they belong to. */
  issues: ReadonlyMap<string, readonly ContentIssue[]>
  /** Moves the focus to the element with this DOM id after the next render. */
  focus: (elementId: string) => void
  /** Tells screen reader users what an action did (moves, removals). */
  announce: (message: string) => void
}

const noop = () => undefined

export const EditorEnvContext = createContext<EditorEnv>({
  readOnly: true,
  issues: new Map(),
  focus: noop,
  announce: noop,
})

export function useEditorEnv(): EditorEnv {
  return useContext(EditorEnvContext)
}

/** The names in scope at a place in the content: fields, computed fields, enclosing repeat items. */
export const ScopeContext = createContext<readonly ScopeVariable[]>([])

export function useScope(): readonly ScopeVariable[] {
  return useContext(ScopeContext)
}

/** The variables of the class profile fields. */
export function fieldVariables(fields: TemplateFields | undefined): ScopeVariable[] {
  return (fields?.fields ?? []).map((f) => ({
    path: f.key,
    label: f.label,
    kind: f.fieldType.type,
    multiple: f.multiple,
    source: 'field' as const,
    options:
      f.fieldType.type === 'Selection'
        ? (f.fieldType.options ?? []).map((o) => ({ value: o.code, label: o.label }))
        : undefined,
  }))
}

/** The variable a repeat adds for its item, typed like an element of its collection if known. */
export function itemVariable(
  vars: readonly ScopeVariable[],
  collection: string,
  itemName: string,
): ScopeVariable {
  const list = vars.find((v) => v.path === collection.trim())
  return {
    path: itemName,
    label: itemName,
    kind: list?.multiple ? list.kind : 'Unknown',
    multiple: false,
    source: 'item',
    options: list?.options,
  }
}

/** DOM id of the element that shows an editor node (for focus and "go to" links). */
export function nodeId(uid: string): string {
  return `tpl-node-${uid}`
}

/** Native selects styled like the inputs. */
export const selectClass =
  'h-8 rounded-lg border border-input bg-background px-2 text-sm disabled:opacity-50 aria-invalid:border-destructive'

/** The DOM id to focus when a block was added: its text, or the block itself. */
export function focusTargetOf(block: EditorBlock): string {
  switch (block.type) {
    case 'Heading':
    case 'Paragraph':
      return `${nodeId(block.uid)}-text`
    case 'ClauseRef':
      return `${nodeId(block.uid)}-key`
    default:
      return nodeId(block.uid)
  }
}
