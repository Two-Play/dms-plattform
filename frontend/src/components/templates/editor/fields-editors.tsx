import { Plus, Trash2 } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { IssueList } from './block-editor'
import {
  move,
  newUid,
  REFERENCES_TARGET,
  referenceSources,
  removeAt,
  replaceAt,
  type EditorComputed,
  type EditorReference,
} from './content-model'
import { nodeId, ScopeContext, selectClass, useEditorEnv } from './editor-context'
import { ComputedExpressionEditor } from './expression-editors'
import { isIdentifier, type ScopeVariable } from './expressions'
import { ReorderControls } from './reorder'
import { useDragReorder } from './use-drag-reorder'

/**
 * Computed fields (business concept 4.2): a name and a calculation. They are evaluated in order, so each
 * one sees the fields and the computed fields before it.
 */
export function ComputedEditor({
  computed,
  fields,
  onChange,
}: {
  computed: readonly EditorComputed[]
  fields: readonly ScopeVariable[]
  onChange: (computed: EditorComputed[]) => void
}) {
  const env = useEditorEnv()
  const reorder = useDragReorder((from, to) => {
    onChange(move(computed, from, to))
  })
  const fieldKeys = new Set(fields.map((f) => f.path))

  return (
    <div className="space-y-3">
      {computed.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('templateEditor.computed.none')}</p>
      )}
      <ol aria-label={t('templateEditor.computed.list')} className="space-y-3">
        {computed.map((c, i) => {
          const id = nodeId(c.uid)
          const issues = env.issues.get(c.uid)
          const name =
            c.key === '' ? t('templateEditor.computed.numbered', { n: String(i + 1) }) : c.key
          const scope: ScopeVariable[] = [
            ...fields,
            ...computed.slice(0, i).map((x) => ({
              path: x.key,
              label: x.key,
              kind: 'Unknown' as const,
              multiple: false,
              source: 'computed' as const,
            })),
          ]
          const keyProblem = !isIdentifier(c.key)
            ? 'identifier'
            : fieldKeys.has(c.key) || computed.some((x, k) => k < i && x.key === c.key)
              ? 'taken'
              : undefined
          return (
            <li
              key={c.uid}
              id={id}
              tabIndex={-1}
              {...reorder.itemProps(i)}
              className={cn(
                'space-y-2 rounded-lg border p-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                issues && 'border-destructive/60',
                reorder.over === i && 'ring-2 ring-primary',
              )}
            >
              <div className="flex flex-wrap items-end gap-2">
                <ReorderControls
                  id={id}
                  name={name}
                  index={i}
                  count={computed.length}
                  handleProps={reorder.handleProps(i)}
                  onMove={(to, control) => {
                    onChange(move(computed, i, to))
                    env.announce(
                      t('templateEditor.move.moved', {
                        name,
                        position: String(to + 1),
                        count: String(computed.length),
                      }),
                    )
                    env.focus(control)
                  }}
                />
                <div className="space-y-1">
                  <Label htmlFor={`${id}-key`}>{t('templateEditor.computed.key')}</Label>
                  <Input
                    id={`${id}-key`}
                    value={c.key}
                    readOnly={env.readOnly}
                    aria-invalid={
                      keyProblem || issues?.some((x) => x.location.part === 'key')
                        ? true
                        : undefined
                    }
                    aria-describedby={`${id}-key-help`}
                    onChange={(e) => {
                      onChange(replaceAt(computed, i, { ...c, key: e.target.value }))
                    }}
                    className="w-48 font-mono"
                  />
                </div>
                {!env.readOnly && (
                  <Button
                    size="xs"
                    variant="ghost"
                    className="ml-auto"
                    aria-label={t('templateEditor.computed.remove', { name })}
                    onClick={() => {
                      onChange(removeAt(computed, i))
                      env.announce(t('templateEditor.block.removed', { name }))
                      env.focus('tpl-add-computed')
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                )}
                <p
                  id={`${id}-key-help`}
                  className={cn(
                    'w-full text-xs',
                    keyProblem ? 'text-destructive' : 'text-muted-foreground',
                  )}
                >
                  {keyProblem === 'identifier'
                    ? t('templateEditor.identifier')
                    : keyProblem === 'taken'
                      ? t('templateEditor.computed.taken')
                      : t('templateEditor.computed.keyHint')}
                </p>
              </div>
              <ScopeContext value={scope}>
                <ComputedExpressionEditor
                  id={`${id}-expression`}
                  value={c.expression}
                  invalid={issues?.some((x) => x.location.part === 'expression')}
                  onChange={(expression) => {
                    onChange(replaceAt(computed, i, { ...c, expression }))
                  }}
                />
              </ScopeContext>
              <IssueList id={`${id}-issues`} issues={issues} />
            </li>
          )
        })}
      </ol>
      {!env.readOnly && (
        <Button
          id="tpl-add-computed"
          variant="outline"
          size="sm"
          onClick={() => {
            const c: EditorComputed = { uid: newUid(), key: '', expression: '' }
            onChange([...computed, c])
            env.focus(`${nodeId(c.uid)}-key`)
          }}
        >
          <Plus aria-hidden="true" />
          {t('templateEditor.computed.add')}
        </Button>
      )}
    </div>
  )
}

/**
 * Reference fields (ADR 0023): the author picks an entry of a master data source, and its attributes are
 * taken over into profile fields. One mapping takes over the entry's `id`.
 */
export function ReferencesEditor({
  references,
  fields,
  onChange,
}: {
  references: readonly EditorReference[]
  fields: readonly ScopeVariable[]
  onChange: (references: EditorReference[]) => void
}) {
  const env = useEditorEnv()
  const general = env.issues.get(REFERENCES_TARGET)

  return (
    <div className="space-y-3">
      <IssueList id="tpl-references-issues" issues={general} />
      {references.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('templateEditor.references.none')}</p>
      )}
      {references.map((r, i) => {
        const id = nodeId(r.uid)
        const issues = env.issues.get(r.uid)
        const attributes = referenceSources[r.source] ?? []
        const name =
          r.label || r.key || t('templateEditor.references.numbered', { n: String(i + 1) })
        const update = (next: EditorReference) => {
          onChange(replaceAt(references, i, next))
        }
        const hasId = r.fields.some((m) => m.attribute === 'id')
        return (
          <fieldset
            key={r.uid}
            id={id}
            tabIndex={-1}
            className={cn(
              'space-y-2 rounded-lg border p-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              issues && 'border-destructive/60',
            )}
          >
            <legend className="px-1 text-sm font-medium">{name}</legend>
            <div className="flex flex-wrap items-end gap-2">
              <div className="space-y-1">
                <Label htmlFor={`${id}-key`}>{t('templateEditor.references.key')}</Label>
                <Input
                  id={`${id}-key`}
                  value={r.key}
                  readOnly={env.readOnly}
                  aria-invalid={!isIdentifier(r.key) ? true : undefined}
                  onChange={(e) => {
                    update({ ...r, key: e.target.value })
                  }}
                  className="w-44 font-mono"
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor={`${id}-label`}>{t('templateEditor.references.label')}</Label>
                <Input
                  id={`${id}-label`}
                  value={r.label}
                  readOnly={env.readOnly}
                  onChange={(e) => {
                    update({ ...r, label: e.target.value })
                  }}
                  className="w-56"
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor={`${id}-source`}>{t('templateEditor.references.source')}</Label>
                <select
                  id={`${id}-source`}
                  value={r.source}
                  disabled={env.readOnly}
                  onChange={(e) => {
                    update({ ...r, source: e.target.value })
                  }}
                  className={selectClass}
                >
                  {!(r.source in referenceSources) && <option value={r.source}>{r.source}</option>}
                  {Object.keys(referenceSources).map((s) => (
                    <option key={s} value={s}>
                      {t('templateEditor.references.partnerMaster')}
                    </option>
                  ))}
                </select>
              </div>
              {!env.readOnly && (
                <Button
                  size="xs"
                  variant="ghost"
                  className="ml-auto"
                  aria-label={t('templateEditor.references.remove', { name })}
                  onClick={() => {
                    onChange(removeAt(references, i))
                    env.announce(t('templateEditor.block.removed', { name }))
                    env.focus('tpl-add-reference')
                  }}
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              )}
            </div>
            <table className="text-sm">
              <caption className="sr-only">
                {t('templateEditor.references.mappings', { name })}
              </caption>
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="pr-2 font-medium">
                    {t('templateEditor.references.attribute')}
                  </th>
                  <th scope="col" className="pr-2 font-medium">
                    {t('templateEditor.references.field')}
                  </th>
                  <th scope="col" className="sr-only">
                    {t('templateEditor.table.rowActions')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {r.fields.map((m, k) => {
                  const n = String(k + 1)
                  const setMapping = (attribute: string, field: string) => {
                    update({ ...r, fields: replaceAt(r.fields, k, { ...m, attribute, field }) })
                  }
                  return (
                    <tr key={m.uid}>
                      <td className="py-0.5 pr-2">
                        <select
                          aria-label={t('templateEditor.references.attributeN', { n })}
                          value={m.attribute}
                          disabled={env.readOnly}
                          onChange={(e) => {
                            setMapping(e.target.value, m.field)
                          }}
                          className={selectClass}
                        >
                          {!attributes.includes(m.attribute) && (
                            <option value={m.attribute}>{m.attribute}</option>
                          )}
                          {attributes.map((a) => (
                            <option key={a} value={a}>
                              {a}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="py-0.5 pr-2">
                        <select
                          aria-label={t('templateEditor.references.fieldN', { n })}
                          value={m.field}
                          disabled={env.readOnly}
                          onChange={(e) => {
                            setMapping(m.attribute, e.target.value)
                          }}
                          className={selectClass}
                        >
                          <option value="">{t('templateEditor.expression.chooseField')}</option>
                          {m.field !== '' && !fields.some((f) => f.path === m.field) && (
                            <option value={m.field}>
                              {t('templateEditor.expression.unknown', { path: m.field })}
                            </option>
                          )}
                          {fields.map((f) => (
                            <option key={f.path} value={f.path}>
                              {`${f.label} (${f.path})`}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        {!env.readOnly && (
                          <Button
                            size="icon-xs"
                            variant="ghost"
                            aria-label={t('templateEditor.references.removeMapping', { n })}
                            onClick={() => {
                              update({ ...r, fields: removeAt(r.fields, k) })
                            }}
                          >
                            <Trash2 aria-hidden="true" />
                          </Button>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {!hasId && (
              <p className="text-xs text-destructive">{t('templateEditor.references.needsId')}</p>
            )}
            {!env.readOnly && (
              <Button
                size="xs"
                variant="outline"
                onClick={() => {
                  const used = new Set(r.fields.map((m) => m.attribute))
                  const attribute = attributes.find((a) => !used.has(a)) ?? attributes[0] ?? 'id'
                  update({ ...r, fields: [...r.fields, { uid: newUid(), attribute, field: '' }] })
                }}
              >
                <Plus aria-hidden="true" />
                {t('templateEditor.references.addMapping')}
              </Button>
            )}
            <IssueList id={`${id}-issues`} issues={issues} />
          </fieldset>
        )
      })}
      {!env.readOnly && (
        <Button
          id="tpl-add-reference"
          variant="outline"
          size="sm"
          onClick={() => {
            const r: EditorReference = {
              uid: newUid(),
              key: '',
              label: '',
              source: 'partner-master',
              fields: [{ uid: newUid(), attribute: 'id', field: '' }],
            }
            onChange([...references, r])
            env.focus(`${nodeId(r.uid)}-key`)
          }}
        >
          <Plus aria-hidden="true" />
          {t('templateEditor.references.add')}
        </Button>
      )}
    </div>
  )
}
