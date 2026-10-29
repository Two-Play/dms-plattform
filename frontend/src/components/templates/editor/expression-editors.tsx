import { Plus, Trash2 } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { newUid } from './content-model'
import { selectClass, useEditorEnv, useScope } from './editor-context'
import {
  buildArithmetic,
  buildCondition,
  insertAtCaret,
  needsValue,
  numericPaths,
  operandProblem,
  operatorsFor,
  parseArithmetic,
  parseCondition,
  quickCheck,
  ruleProblem,
  type ArithmeticForm,
  type ArithmeticOperator,
  type ConditionForm,
  type Operand,
  type Operator,
  type Rule,
  type ScopeVariable,
} from './expressions'

function ModeSwitch({
  mode,
  onForm,
  onExpression,
  formAvailable,
}: {
  mode: 'form' | 'expression'
  onForm: () => void
  onExpression: () => void
  formAvailable: boolean
}) {
  const { readOnly } = useEditorEnv()
  if (readOnly) return null
  return mode === 'form' ? (
    <Button size="xs" variant="ghost" onClick={onExpression}>
      {t('templateEditor.expression.asExpression')}
    </Button>
  ) : (
    <Button
      size="xs"
      variant="ghost"
      disabled={!formAvailable}
      title={formAvailable ? undefined : t('templateEditor.expression.noForm')}
      onClick={onForm}
    >
      {t('templateEditor.expression.asForm')}
    </Button>
  )
}

/**
 * Guided input for a free CEL expression: the fields in scope can be inserted at the caret, brackets and
 * strings are checked while typing; names and types are checked by the server against the class.
 */
export function ExpressionInput({
  id,
  label,
  value,
  onChange,
  invalid,
  hint,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  invalid?: boolean
  hint?: string
}) {
  const { readOnly } = useEditorEnv()
  const vars = useScope()
  const area = useRef<HTMLTextAreaElement>(null)
  const [insert, setInsert] = useState('')
  const problem = quickCheck(value)
  const shown = problem === 'empty' ? undefined : problem

  function add(path: string) {
    const el = area.current
    const start = el?.selectionStart ?? value.length
    const end = el?.selectionEnd ?? value.length
    const next = insertAtCaret(value, start, end, path)
    onChange(next.text)
    requestAnimationFrame(() => {
      area.current?.focus()
      area.current?.setSelectionRange(next.caret, next.caret)
    })
  }

  return (
    <div className="space-y-1">
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        ref={area}
        id={id}
        value={value}
        readOnly={readOnly}
        rows={2}
        spellCheck={false}
        aria-invalid={invalid === true || shown !== undefined ? true : undefined}
        aria-describedby={`${id}-help`}
        onChange={(e) => {
          onChange(e.target.value)
        }}
        className="min-h-0 font-mono text-xs"
      />
      <div id={`${id}-help`} className="space-y-1 text-xs text-muted-foreground">
        {shown && (
          <p className="text-destructive">{t(`templateEditor.expression.problem.${shown}`)}</p>
        )}
        {hint && <p>{hint}</p>}
      </div>
      {!readOnly && vars.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <Label htmlFor={`${id}-insert`} className="text-xs font-normal text-muted-foreground">
            {t('templateEditor.expression.insertField')}
          </Label>
          <select
            id={`${id}-insert`}
            value={insert}
            onChange={(e) => {
              setInsert(e.target.value)
            }}
            className={selectClass}
          >
            <option value="">{t('templateEditor.expression.chooseField')}</option>
            {vars.map((v) => (
              <option key={v.path} value={v.path}>
                {v.source === 'field' ? `${v.label} (${v.path})` : v.path}
              </option>
            ))}
          </select>
          <Button
            size="xs"
            variant="outline"
            disabled={insert === ''}
            onClick={() => {
              add(insert)
              setInsert('')
            }}
          >
            {t('templateEditor.expression.insert')}
          </Button>
        </div>
      )}
    </div>
  )
}

function FieldSelect({
  id,
  label,
  value,
  options,
  onChange,
  invalid,
}: {
  id: string
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  onChange: (value: string) => void
  invalid?: boolean
}) {
  const { readOnly } = useEditorEnv()
  const known = value === '' || options.some((o) => o.value === value)
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-xs">
        {label}
      </Label>
      <select
        id={id}
        value={value}
        disabled={readOnly}
        aria-invalid={invalid ? true : undefined}
        onChange={(e) => {
          onChange(e.target.value)
        }}
        className={selectClass}
      >
        <option value="">{t('templateEditor.expression.chooseField')}</option>
        {!known && (
          <option value={value}>{t('templateEditor.expression.unknown', { path: value })}</option>
        )}
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  )
}

function variableOptions(vars: readonly ScopeVariable[]) {
  return vars.map((v) => ({
    value: v.path,
    label: v.source === 'field' ? `${v.label} (${v.path})` : v.path,
  }))
}

function RuleRow({
  id,
  rule,
  index,
  onChange,
  onRemove,
}: {
  id: string
  rule: Rule
  index: number
  onChange: (rule: Rule) => void
  onRemove: () => void
}) {
  const { readOnly } = useEditorEnv()
  const vars = useScope()
  const v = vars.find((x) => x.path === rule.field)
  const operators = operatorsFor(v)
  const problem = ruleProblem(rule, v)
  const n = String(index + 1)

  function valueInput() {
    if (!needsValue(rule.operator)) return null
    const common = {
      id: `${id}-value`,
      value: rule.value,
      readOnly,
      'aria-invalid':
        problem === 'number' || problem === 'date' || problem === 'value' ? true : undefined,
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
        onChange({ ...rule, value: e.target.value })
      },
    }
    const options = v?.options ?? []
    return (
      <div className="space-y-1">
        <Label htmlFor={`${id}-value`} className="text-xs">
          {t('templateEditor.condition.value', { n })}
        </Label>
        {options.length > 0 ? (
          <select {...common} disabled={readOnly} className={selectClass}>
            <option value="">{t('templateEditor.condition.chooseValue')}</option>
            {options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        ) : (
          <Input
            {...common}
            type={v?.kind === 'Date' ? 'date' : 'text'}
            inputMode={v?.kind === 'Number' || v?.kind === 'Amount' ? 'decimal' : undefined}
            className="w-40"
          />
        )}
      </div>
    )
  }

  return (
    <li className="flex flex-wrap items-end gap-2">
      <FieldSelect
        id={`${id}-field`}
        label={t('templateEditor.condition.field', { n })}
        value={rule.field}
        options={variableOptions(vars)}
        invalid={problem === 'field'}
        onChange={(field) => {
          const next = vars.find((x) => x.path === field)
          const ops = operatorsFor(next)
          onChange({
            field,
            operator: ops.includes(rule.operator) ? rule.operator : (ops[0] ?? 'eq'),
            value: '',
          })
        }}
      />
      <div className="space-y-1">
        <Label htmlFor={`${id}-op`} className="text-xs">
          {t('templateEditor.condition.operator', { n })}
        </Label>
        <select
          id={`${id}-op`}
          value={rule.operator}
          disabled={readOnly}
          onChange={(e) => {
            onChange({ ...rule, operator: e.target.value as Operator })
          }}
          className={selectClass}
        >
          {(operators.includes(rule.operator) ? operators : [rule.operator, ...operators]).map(
            (op) => (
              <option key={op} value={op}>
                {t(`templateEditor.operators.${op}`)}
              </option>
            ),
          )}
        </select>
      </div>
      {valueInput()}
      {!readOnly && (
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label={t('templateEditor.condition.removeRule', { n })}
          onClick={onRemove}
        >
          <Trash2 aria-hidden="true" />
        </Button>
      )}
      {problem && problem !== 'field' && (
        <p className="w-full text-xs text-destructive">
          {t(`templateEditor.condition.problem.${problem}`)}
        </p>
      )}
    </li>
  )
}

/** A condition as rules (field, comparison, value), or as a guided CEL expression. */
export function ConditionEditor({
  id,
  value,
  onChange,
  invalid,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  invalid?: boolean
}) {
  const { readOnly } = useEditorEnv()
  const vars = useScope()
  const [form, setForm] = useState<ConditionForm | undefined>(() => parseCondition(value, vars))
  const [mode, setMode] = useState<'form' | 'expression'>(form ? 'form' : 'expression')
  const formOfValue = parseCondition(value, vars)
  const [ruleKeys, setRuleKeys] = useState<string[]>(() => (form?.rules ?? []).map(() => newUid()))

  function update(next: ConditionForm, nextKeys = ruleKeys) {
    setForm(next)
    setRuleKeys(nextKeys)
    onChange(buildCondition(next, vars))
  }

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-sm font-medium" id={`${id}-label`}>
        {t('templateEditor.condition.title')}
      </span>
      <ModeSwitch
        mode={mode}
        formAvailable={formOfValue !== undefined}
        onExpression={() => {
          setMode('expression')
        }}
        onForm={() => {
          if (!formOfValue) return
          setForm(formOfValue)
          setRuleKeys(formOfValue.rules.map(() => newUid()))
          setMode('form')
        }}
      />
    </div>
  )

  if (mode === 'expression' || !form) {
    return (
      <div className="space-y-1">
        {header}
        <ExpressionInput
          id={id}
          label={t('templateEditor.condition.expression')}
          value={value}
          onChange={onChange}
          invalid={invalid}
          hint={t('templateEditor.condition.hint')}
        />
      </div>
    )
  }

  return (
    <div role="group" aria-labelledby={`${id}-label`} className="space-y-2">
      {header}
      {form.rules.length > 1 && (
        <div className="flex items-center gap-2">
          <Label htmlFor={`${id}-join`} className="text-xs">
            {t('templateEditor.condition.join')}
          </Label>
          <select
            id={`${id}-join`}
            value={form.join}
            disabled={readOnly}
            onChange={(e) => {
              update({ ...form, join: e.target.value === 'or' ? 'or' : 'and' })
            }}
            className={selectClass}
          >
            <option value="and">{t('templateEditor.condition.all')}</option>
            <option value="or">{t('templateEditor.condition.any')}</option>
          </select>
        </div>
      )}
      <ol className="space-y-2">
        {form.rules.map((rule, i) => (
          <RuleRow
            key={ruleKeys[i] ?? String(i)}
            id={`${id}-rule-${ruleKeys[i] ?? String(i)}`}
            index={i}
            rule={rule}
            onChange={(r) => {
              update({ ...form, rules: form.rules.map((x, k) => (k === i ? r : x)) })
            }}
            onRemove={() => {
              update(
                { ...form, rules: form.rules.filter((_, k) => k !== i) },
                ruleKeys.filter((_, k) => k !== i),
              )
            }}
          />
        ))}
      </ol>
      {form.rules.length === 0 && (
        <p className="text-xs text-destructive">{t('templateEditor.condition.noRules')}</p>
      )}
      {!readOnly && (
        <Button
          size="xs"
          variant="outline"
          onClick={() => {
            const first = vars[0]
            const rule: Rule = {
              field: first?.path ?? '',
              operator: operatorsFor(first)[0] ?? 'eq',
              value: '',
            }
            update({ ...form, rules: [...form.rules, rule] }, [...ruleKeys, newUid()])
          }}
        >
          <Plus aria-hidden="true" />
          {t('templateEditor.condition.addRule')}
        </Button>
      )}
      <p className="font-mono text-xs text-muted-foreground" aria-live="polite">
        {value === '' ? '' : t('templateEditor.condition.preview', { expression: value })}
      </p>
    </div>
  )
}

function OperandInput({
  id,
  label,
  operand,
  options,
  onChange,
}: {
  id: string
  label: string
  operand: Operand
  options: readonly { value: string; label: string }[]
  onChange: (o: Operand) => void
}) {
  const { readOnly } = useEditorEnv()
  const NUMBER = '__number__'
  return (
    <div className="flex items-end gap-1">
      <div className="space-y-1">
        <Label htmlFor={id} className="text-xs">
          {label}
        </Label>
        <select
          id={id}
          value={operand.kind === 'number' ? NUMBER : operand.path}
          disabled={readOnly}
          aria-invalid={operand.kind === 'field' && operand.path === '' ? true : undefined}
          onChange={(e) => {
            onChange(
              e.target.value === NUMBER
                ? { kind: 'number', value: '' }
                : { kind: 'field', path: e.target.value },
            )
          }}
          className={selectClass}
        >
          <option value="">{t('templateEditor.expression.chooseField')}</option>
          {operand.kind === 'field' &&
            operand.path !== '' &&
            !options.some((o) => o.value === operand.path) && (
              <option value={operand.path}>
                {t('templateEditor.expression.unknown', { path: operand.path })}
              </option>
            )}
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
          <option value={NUMBER}>{t('templateEditor.computed.number')}</option>
        </select>
      </div>
      {operand.kind === 'number' && (
        <Input
          aria-label={t('templateEditor.computed.numberValue', { label })}
          value={operand.value}
          readOnly={readOnly}
          inputMode="decimal"
          aria-invalid={operandProblem(operand) ? true : undefined}
          onChange={(e) => {
            onChange({ kind: 'number', value: e.target.value })
          }}
          className="w-24"
        />
      )}
    </div>
  )
}

const arithmeticOperators: ArithmeticOperator[] = ['+', '-', '*', '/']

/** The expression of a computed field: arithmetic over numbers in a form, or a guided expression. */
export function ComputedExpressionEditor({
  id,
  value,
  onChange,
  invalid,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  invalid?: boolean
}) {
  const { readOnly } = useEditorEnv()
  const vars = useScope()
  const empty: ArithmeticForm = { first: { kind: 'field', path: '' }, rest: [] }
  const [form, setForm] = useState<ArithmeticForm | undefined>(() =>
    value.trim() === '' ? empty : parseArithmetic(value, vars),
  )
  const [mode, setMode] = useState<'form' | 'expression'>(form ? 'form' : 'expression')
  const formOfValue = value.trim() === '' ? empty : parseArithmetic(value, vars)
  const options = numericPaths(vars).map((o) => ({
    value: o.value,
    label: `${o.label} (${o.value})`,
  }))
  const groupId = useId()

  function update(next: ArithmeticForm) {
    setForm(next)
    onChange(buildArithmetic(next))
  }

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-sm font-medium" id={groupId}>
        {t('templateEditor.computed.calculation')}
      </span>
      <ModeSwitch
        mode={mode}
        formAvailable={formOfValue !== undefined}
        onExpression={() => {
          setMode('expression')
        }}
        onForm={() => {
          if (!formOfValue) return
          setForm(formOfValue)
          setMode('form')
        }}
      />
    </div>
  )

  if (mode === 'expression' || !form) {
    return (
      <div className="space-y-1">
        {header}
        <ExpressionInput
          id={id}
          label={t('templateEditor.computed.expression')}
          value={value}
          onChange={onChange}
          invalid={invalid}
          hint={t('templateEditor.computed.hint')}
        />
      </div>
    )
  }

  return (
    <div role="group" aria-labelledby={groupId} className="space-y-2">
      {header}
      <div className="flex flex-wrap items-end gap-2">
        <OperandInput
          id={`${id}-0`}
          label={t('templateEditor.computed.operand', { n: '1' })}
          operand={form.first}
          options={options}
          onChange={(first) => {
            update({ ...form, first })
          }}
        />
        {form.rest.map((r, i) => (
          <div key={i} className="flex items-end gap-2">
            <div className="space-y-1">
              <Label htmlFor={`${id}-op-${String(i)}`} className="text-xs">
                {t('templateEditor.computed.operator', { n: String(i + 1) })}
              </Label>
              <select
                id={`${id}-op-${String(i)}`}
                value={r.op}
                disabled={readOnly}
                onChange={(e) => {
                  const op = e.target.value as ArithmeticOperator
                  update({
                    ...form,
                    rest: form.rest.map((x, k) => (k === i ? { ...x, op } : x)),
                  })
                }}
                className={selectClass}
              >
                {arithmeticOperators.map((op) => (
                  <option key={op} value={op}>
                    {t(`templateEditor.computed.ops.${op}`)}
                  </option>
                ))}
              </select>
            </div>
            <OperandInput
              id={`${id}-${String(i + 1)}`}
              label={t('templateEditor.computed.operand', { n: String(i + 2) })}
              operand={r.operand}
              options={options}
              onChange={(operand) => {
                update({
                  ...form,
                  rest: form.rest.map((x, k) => (k === i ? { ...x, operand } : x)),
                })
              }}
            />
            {!readOnly && (
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={t('templateEditor.computed.removeStep', { n: String(i + 2) })}
                onClick={() => {
                  update({ ...form, rest: form.rest.filter((_, k) => k !== i) })
                }}
              >
                <Trash2 aria-hidden="true" />
              </Button>
            )}
          </div>
        ))}
        {!readOnly && (
          <Button
            size="xs"
            variant="outline"
            onClick={() => {
              update({
                ...form,
                rest: [...form.rest, { op: '*', operand: { kind: 'field', path: '' } }],
              })
            }}
          >
            <Plus aria-hidden="true" />
            {t('templateEditor.computed.addStep')}
          </Button>
        )}
      </div>
      <p className="font-mono text-xs text-muted-foreground">
        {value === '' ? '' : t('templateEditor.condition.preview', { expression: value })}
      </p>
    </div>
  )
}

/** The list a repeat runs over: a list field, or a guided expression. */
export function CollectionEditor({
  id,
  value,
  onChange,
  invalid,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  invalid?: boolean
}) {
  const vars = useScope()
  const lists = vars.filter((v) => v.multiple)
  const fits = (v: string) => v.trim() === '' || lists.some((l) => l.path === v.trim())
  const [mode, setMode] = useState<'form' | 'expression'>(fits(value) ? 'form' : 'expression')

  const header = (
    <div className="flex flex-wrap items-center justify-end gap-2">
      <ModeSwitch
        mode={mode}
        formAvailable={fits(value)}
        onExpression={() => {
          setMode('expression')
        }}
        onForm={() => {
          setMode('form')
        }}
      />
    </div>
  )

  if (mode === 'expression') {
    return (
      <div className="space-y-1">
        <ExpressionInput
          id={id}
          label={t('templateEditor.repeat.collectionExpression')}
          value={value}
          onChange={onChange}
          invalid={invalid}
          hint={t('templateEditor.repeat.hint')}
        />
        {header}
      </div>
    )
  }
  return (
    <div className="flex flex-wrap items-end gap-2">
      <FieldSelect
        id={id}
        label={t('templateEditor.repeat.collection')}
        value={value}
        options={variableOptions(lists)}
        onChange={onChange}
        invalid={invalid}
      />
      {header}
    </div>
  )
}
