/**
 * Forms for the CEL expressions of a template (ADR 0020, DMS-128): conditions as rules, computed fields as
 * arithmetic, repeat collections as a list field. Each form builds an expression and reads back the
 * expressions it builds; anything else stays a free expression with a guided input.
 */

/** Value kinds of the class profile fields, as the CEL type mapping of ADR 0020 sees them. */
export type ValueKind =
  | 'Text'
  | 'Number'
  | 'Amount'
  | 'Date'
  | 'YesNo'
  | 'Selection'
  | 'PersonRef'
  | 'DocumentRef'
  | 'Unknown'

export interface ScopeOption {
  value: string
  label: string
}

/** A name an expression or placeholder can use: a profile field, a computed field or a repeat item. */
export interface ScopeVariable {
  path: string
  label: string
  kind: ValueKind
  multiple: boolean
  source: 'field' | 'computed' | 'item'
  options?: ScopeOption[]
}

/** Lower camel case names for computed fields and repeat items, as the compiler requires. */
export const identifierPattern = /^[a-z][a-zA-Z0-9]{0,62}$/

export function isIdentifier(name: string): boolean {
  return identifierPattern.test(name)
}

/** Placeholder formatters by value kind (`TemplateCompiler.formatters`); `plain` fits every kind. */
export const formatters: Record<string, readonly ValueKind[]> = {
  plain: [],
  number: ['Number'],
  amount: ['Amount'],
  'date:long': ['Date'],
  'date:short': ['Date'],
}

export function formatsFor(kind: ValueKind): string[] {
  return Object.entries(formatters)
    .filter(([, kinds]) => kinds.length === 0 || kind === 'Unknown' || kinds.includes(kind))
    .map(([name]) => name)
}

/** Paths a placeholder can name for a variable: amounts also by their parts. */
export function placeholderPaths(v: ScopeVariable): { path: string; kind: ValueKind }[] {
  if (v.kind === 'Amount' && !v.multiple) {
    return [
      { path: v.path, kind: 'Amount' },
      { path: `${v.path}.amount`, kind: 'Number' },
      { path: `${v.path}.currency`, kind: 'Text' },
    ]
  }
  return [{ path: v.path, kind: v.multiple ? 'Unknown' : v.kind }]
}

// ---------------------------------------------------------------------------------------------
// Literals
// ---------------------------------------------------------------------------------------------

/** A CEL string literal. CEL accepts the escapes JSON produces. */
export function stringLiteral(value: string): string {
  return JSON.stringify(value)
}

const numberPattern = /^-?\d+(\.\d+)?$/

/** Whether a form value is a number the form can write (digits, optional decimal point). */
export function isNumber(value: string): boolean {
  return numberPattern.test(value.trim())
}

/** A CEL double literal: profile numbers are doubles, so `12` is written `12.0` (ADR 0020). */
export function numberLiteral(value: string): string {
  const v = value.trim()
  return v.includes('.') ? v : `${v}.0`
}

function numberValue(literal: string): string {
  return literal.endsWith('.0') ? literal.slice(0, -2) : literal
}

const datePattern = /^\d{4}-\d{2}-\d{2}$/

export function isDate(value: string): boolean {
  return datePattern.test(value)
}

function dateLiteral(value: string): string {
  return `timestamp("${value}T00:00:00Z")`
}

// ---------------------------------------------------------------------------------------------
// Conditions
// ---------------------------------------------------------------------------------------------

export type Operator =
  'eq' | 'ne' | 'lt' | 'le' | 'gt' | 'ge' | 'isTrue' | 'isFalse' | 'contains' | 'present' | 'absent'

const comparisons: Record<string, Operator> = {
  '==': 'eq',
  '!=': 'ne',
  '<': 'lt',
  '<=': 'le',
  '>': 'gt',
  '>=': 'ge',
}
const symbols: Partial<Record<Operator, string>> = {
  eq: '==',
  ne: '!=',
  lt: '<',
  le: '<=',
  gt: '>',
  ge: '>=',
}

export interface Rule {
  field: string
  operator: Operator
  value: string
}

export interface ConditionForm {
  join: 'and' | 'or'
  rules: Rule[]
}

/** Operators that need a value. */
export function needsValue(op: Operator): boolean {
  return !['isTrue', 'isFalse', 'present', 'absent'].includes(op)
}

/** The operators a rule offers for a variable. */
export function operatorsFor(v: ScopeVariable | undefined): Operator[] {
  const presence: Operator[] = v?.source === 'field' ? ['present', 'absent'] : []
  if (!v) return ['eq', 'ne']
  if (v.multiple) return ['contains', ...presence]
  switch (v.kind) {
    case 'YesNo':
      return ['isTrue', 'isFalse', ...presence]
    case 'Number':
    case 'Amount':
    case 'Date':
    case 'Unknown':
      return ['eq', 'ne', 'lt', 'le', 'gt', 'ge', ...presence]
    default:
      return ['eq', 'ne', ...presence]
  }
}

/** The kind of the values a rule compares with. */
function valueKind(v: ScopeVariable | undefined): ValueKind {
  if (!v) return 'Text'
  if (v.kind === 'Amount') return 'Number'
  return v.kind
}

/** Why a rule cannot be written yet, or undefined if it can. */
export function ruleProblem(
  rule: Rule,
  v: ScopeVariable | undefined,
): 'field' | 'number' | 'date' | 'value' | undefined {
  if (rule.field === '' || !v) return 'field'
  if (!needsValue(rule.operator)) return undefined
  switch (valueKind(v)) {
    case 'Number':
      return isNumber(rule.value) ? undefined : 'number'
    case 'Date':
      return isDate(rule.value) ? undefined : 'date'
    case 'Unknown':
      return rule.value.trim() === '' ? 'value' : undefined
    default:
      return undefined
  }
}

function literalFor(value: string, kind: ValueKind): string {
  switch (kind) {
    case 'Number':
      return numberLiteral(value)
    case 'Date':
      return dateLiteral(value)
    case 'Unknown':
      return isNumber(value) ? numberLiteral(value) : stringLiteral(value)
    default:
      return stringLiteral(value)
  }
}

function ruleExpression(rule: Rule, v: ScopeVariable | undefined): string {
  const kind = valueKind(v)
  const lhs = v?.kind === 'Amount' && !v.multiple ? `${rule.field}.amount` : rule.field
  switch (rule.operator) {
    case 'isTrue':
      return rule.field
    case 'isFalse':
      return `!${rule.field}`
    case 'present':
      return `'${rule.field}' in present_fields`
    case 'absent':
      return `!('${rule.field}' in present_fields)`
    case 'contains':
      return `${literalFor(rule.value, kind)} in ${rule.field}`
    default:
      return `${lhs} ${symbols[rule.operator] ?? '=='} ${literalFor(rule.value, kind)}`
  }
}

/** The CEL condition for a form. */
export function buildCondition(form: ConditionForm, vars: readonly ScopeVariable[]): string {
  const byPath = new Map(vars.map((v) => [v.path, v]))
  return form.rules
    .map((r) => ruleExpression(r, byPath.get(r.field)))
    .join(form.join === 'and' ? ' && ' : ' || ')
}

const path = String.raw`[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*`
const literal = String.raw`"(?:[^"\\]|\\.)*"|'[^'\\]*'|-?\d+(?:\.\d+)?|timestamp\("\d{4}-\d{2}-\d{2}T00:00:00Z"\)`
const rulePatterns: { re: RegExp; read: (m: RegExpExecArray) => Rule }[] = [
  {
    re: new RegExp(String.raw`^!\('(${path})' in present_fields\)`),
    read: (m) => ({ field: m[1] ?? '', operator: 'absent', value: '' }),
  },
  {
    re: new RegExp(String.raw`^'(${path})' in present_fields`),
    read: (m) => ({ field: m[1] ?? '', operator: 'present', value: '' }),
  },
  {
    re: new RegExp(String.raw`^(${literal}) in (${path})`),
    read: (m) => ({ field: m[2] ?? '', operator: 'contains', value: literalValue(m[1] ?? '') }),
  },
  {
    re: new RegExp(String.raw`^(${path}) (==|!=|<=|>=|<|>) (${literal})`),
    read: (m) => ({
      field: m[1] ?? '',
      operator: comparisons[m[2] ?? '=='] ?? 'eq',
      value: literalValue(m[3] ?? ''),
    }),
  },
  {
    re: new RegExp(String.raw`^!(${path})`),
    read: (m) => ({ field: m[1] ?? '', operator: 'isFalse', value: '' }),
  },
  {
    re: new RegExp(String.raw`^(${path})(?=$| && | \|\| )`),
    read: (m) => ({ field: m[1] ?? '', operator: 'isTrue', value: '' }),
  },
]

function literalValue(lit: string): string {
  if (lit.startsWith('"')) return JSON.parse(lit) as string
  if (lit.startsWith("'")) return lit.slice(1, -1)
  const date = /^timestamp\("(\d{4}-\d{2}-\d{2})T00:00:00Z"\)$/.exec(lit)
  if (date) return date[1] ?? ''
  return numberValue(lit)
}

/**
 * The form for a condition the form can show: rules of the shapes `buildCondition` writes, joined by
 * one kind of `&&` or `||`. Undefined for anything else (parentheses, functions, arithmetic, …).
 */
export function parseCondition(
  expression: string,
  vars: readonly ScopeVariable[],
): ConditionForm | undefined {
  const source = expression.trim()
  if (source === '') return { join: 'and', rules: [] }
  const byPath = new Map(vars.map((v) => [v.path, v]))
  const rules: Rule[] = []
  let join: 'and' | 'or' | undefined
  let rest = source
  for (;;) {
    let matched: { rule: Rule; length: number } | undefined
    for (const p of rulePatterns) {
      const m = p.re.exec(rest)
      if (m) {
        matched = { rule: p.read(m), length: m[0].length }
        break
      }
    }
    if (!matched) return undefined
    const rule = matched.rule
    // `x.amount < 5.0` is a rule on the amount field `x`.
    if (rule.field.endsWith('.amount') && !byPath.has(rule.field)) {
      const base = rule.field.slice(0, -'.amount'.length)
      if (byPath.get(base)?.kind === 'Amount') rule.field = base
    }
    rules.push(rule)
    rest = rest.slice(matched.length)
    if (rest === '') break
    const next = rest.startsWith(' && ') ? 'and' : rest.startsWith(' || ') ? 'or' : undefined
    if (!next || (join && join !== next)) return undefined
    join = next
    rest = rest.slice(4)
  }
  return { join: join ?? 'and', rules }
}

// ---------------------------------------------------------------------------------------------
// Computed fields: arithmetic over numbers
// ---------------------------------------------------------------------------------------------

export type ArithmeticOperator = '+' | '-' | '*' | '/'

export type Operand = { kind: 'field'; path: string } | { kind: 'number'; value: string }

export interface ArithmeticForm {
  first: Operand
  rest: { op: ArithmeticOperator; operand: Operand }[]
}

/** Numeric paths a computed field can calculate with: numbers, amounts (their `amount`), computed fields. */
export function numericPaths(vars: readonly ScopeVariable[]): ScopeOption[] {
  return vars.flatMap((v) => {
    if (v.multiple) return []
    if (v.kind === 'Number' || v.kind === 'Unknown') return [{ value: v.path, label: v.label }]
    if (v.kind === 'Amount') return [{ value: `${v.path}.amount`, label: v.label }]
    return []
  })
}

function operandText(o: Operand): string {
  return o.kind === 'field' ? o.path : numberLiteral(o.value)
}

export function operandProblem(o: Operand): boolean {
  return o.kind === 'field' ? o.path === '' : !isNumber(o.value)
}

export function buildArithmetic(form: ArithmeticForm): string {
  return [
    operandText(form.first),
    ...form.rest.map((r) => `${r.op} ${operandText(r.operand)}`),
  ].join(' ')
}

function readOperand(token: string, numeric: ReadonlySet<string>): Operand | undefined {
  if (isNumber(token)) return { kind: 'number', value: numberValue(token) }
  if (numeric.has(token)) return { kind: 'field', path: token }
  return undefined
}

/** The form for `a * b + 2.0` style expressions over known numeric paths; undefined otherwise. */
export function parseArithmetic(
  expression: string,
  vars: readonly ScopeVariable[],
): ArithmeticForm | undefined {
  const tokens = expression.trim().split(/\s+/)
  const numeric = new Set(numericPaths(vars).map((o) => o.value))
  const first = readOperand(tokens[0] ?? '', numeric)
  if (!first || tokens.length % 2 === 0) return undefined
  const rest: ArithmeticForm['rest'] = []
  for (let i = 1; i < tokens.length; i += 2) {
    const op = tokens[i]
    const operand = readOperand(tokens[i + 1] ?? '', numeric)
    if (!operand || (op !== '+' && op !== '-' && op !== '*' && op !== '/')) return undefined
    rest.push({ op, operand })
  }
  return { first, rest }
}

// ---------------------------------------------------------------------------------------------
// Free expressions
// ---------------------------------------------------------------------------------------------

/**
 * Problems a free expression has before the server sees it: unbalanced brackets or an open string.
 * Types and names are checked by the server against the document class.
 */
export function quickCheck(expression: string): 'empty' | 'brackets' | 'string' | undefined {
  if (expression.trim() === '') return 'empty'
  const stack: string[] = []
  const pairs: Record<string, string> = { ')': '(', ']': '[', '}': '{' }
  let quote: string | undefined
  for (let i = 0; i < expression.length; i += 1) {
    const c = expression.charAt(i)
    if (quote) {
      if (c === '\\') i += 1
      else if (c === quote) quote = undefined
      continue
    }
    if (c === '"' || c === "'") quote = c
    else if (c === '(' || c === '[' || c === '{') stack.push(c)
    else if (c in pairs) {
      if (stack.pop() !== pairs[c]) return 'brackets'
    }
  }
  if (quote) return 'string'
  return stack.length > 0 ? 'brackets' : undefined
}

/** The text with `insert` placed at the selection, and the caret after it. */
export function insertAtCaret(
  text: string,
  start: number,
  end: number,
  insert: string,
): { text: string; caret: number } {
  const before = text.slice(0, start)
  const after = text.slice(end)
  const pad = before !== '' && !before.endsWith(' ') && !before.endsWith('(') ? ' ' : ''
  const value = `${pad}${insert}`
  return { text: before + value + after, caret: before.length + value.length }
}
