import { describe, expect, it } from 'vitest'
import {
  buildArithmetic,
  buildCondition,
  formatsFor,
  insertAtCaret,
  operatorsFor,
  parseArithmetic,
  parseCondition,
  placeholderPaths,
  quickCheck,
  ruleProblem,
  type ConditionForm,
  type ScopeVariable,
} from './expressions'

const field = (path: string, kind: ScopeVariable['kind'], multiple = false): ScopeVariable => ({
  path,
  label: path,
  kind,
  multiple,
  source: 'field',
})

const vars: ScopeVariable[] = [
  field('termType', 'Selection'),
  field('termMonths', 'Number'),
  field('monthlyFee', 'Amount'),
  field('startDate', 'Date'),
  field('autoRenew', 'YesNo'),
  field('partnerName', 'Text'),
  field('regions', 'Text', true),
  { path: 'total', label: 'total', kind: 'Unknown', multiple: false, source: 'computed' },
]

describe('condition forms', () => {
  const cases: [ConditionForm, string][] = [
    [
      { join: 'and', rules: [{ field: 'termType', operator: 'eq', value: 'fixed' }] },
      'termType == "fixed"',
    ],
    [
      { join: 'and', rules: [{ field: 'partnerName', operator: 'ne', value: 'A "B"' }] },
      'partnerName != "A \\"B\\""',
    ],
    [
      { join: 'and', rules: [{ field: 'termMonths', operator: 'ge', value: '12' }] },
      'termMonths >= 12.0',
    ],
    [
      { join: 'and', rules: [{ field: 'monthlyFee', operator: 'gt', value: '99.5' }] },
      'monthlyFee.amount > 99.5',
    ],
    [
      { join: 'and', rules: [{ field: 'startDate', operator: 'lt', value: '2027-01-01' }] },
      'startDate < timestamp("2027-01-01T00:00:00Z")',
    ],
    [{ join: 'and', rules: [{ field: 'autoRenew', operator: 'isTrue', value: '' }] }, 'autoRenew'],
    [
      { join: 'and', rules: [{ field: 'autoRenew', operator: 'isFalse', value: '' }] },
      '!autoRenew',
    ],
    [
      { join: 'and', rules: [{ field: 'regions', operator: 'contains', value: 'EU' }] },
      '"EU" in regions',
    ],
    [
      { join: 'and', rules: [{ field: 'startDate', operator: 'present', value: '' }] },
      "'startDate' in present_fields",
    ],
    [
      { join: 'and', rules: [{ field: 'startDate', operator: 'absent', value: '' }] },
      "!('startDate' in present_fields)",
    ],
    [
      {
        join: 'or',
        rules: [
          { field: 'termType', operator: 'eq', value: 'fixed' },
          { field: 'total', operator: 'le', value: '5' },
          { field: 'autoRenew', operator: 'isTrue', value: '' },
        ],
      },
      'termType == "fixed" || total <= 5.0 || autoRenew',
    ],
  ]

  it.each(cases)('writes and reads back %j', (form, expression) => {
    expect(buildCondition(form, vars)).toBe(expression)
    expect(parseCondition(expression, vars)).toEqual(form)
  })

  it('reads conditions written by hand in the same shapes', () => {
    expect(parseCondition("termType == 'fixed' && termMonths > 6.0", vars)).toEqual({
      join: 'and',
      rules: [
        { field: 'termType', operator: 'eq', value: 'fixed' },
        { field: 'termMonths', operator: 'gt', value: '6' },
      ],
    })
    expect(parseCondition('', vars)).toEqual({ join: 'and', rules: [] })
  })

  it('leaves everything else to the expression input', () => {
    for (const e of [
      'termMonths * 2.0 > 12.0',
      '(autoRenew || termType == "fixed") && termMonths > 1.0',
      'termType == "a" && autoRenew || termMonths > 1.0',
      'size(regions) > 0',
      'termType ==',
    ]) {
      expect(parseCondition(e, vars)).toBeUndefined()
    }
  })

  it('offers operators by field type and flags missing values', () => {
    const [termType, termMonths, , startDate, autoRenew, , regions] = vars
    expect(operatorsFor(autoRenew)).toEqual(['isTrue', 'isFalse', 'present', 'absent'])
    expect(operatorsFor(regions)).toEqual(['contains', 'present', 'absent'])
    expect(operatorsFor(termType)).toEqual(['eq', 'ne', 'present', 'absent'])
    expect(ruleProblem({ field: 'termMonths', operator: 'gt', value: 'ten' }, termMonths)).toBe(
      'number',
    )
    expect(ruleProblem({ field: 'startDate', operator: 'lt', value: '1.1.2027' }, startDate)).toBe(
      'date',
    )
    expect(ruleProblem({ field: '', operator: 'eq', value: '' }, undefined)).toBe('field')
    expect(ruleProblem({ field: 'autoRenew', operator: 'isTrue', value: '' }, autoRenew)).toBe(
      undefined,
    )
  })
})

describe('computed field forms', () => {
  it('writes and reads back arithmetic over numeric fields and numbers', () => {
    const form = {
      first: { kind: 'field' as const, path: 'monthlyFee.amount' },
      rest: [
        { op: '*' as const, operand: { kind: 'field' as const, path: 'termMonths' } },
        { op: '-' as const, operand: { kind: 'number' as const, value: '10' } },
      ],
    }
    const expression = 'monthlyFee.amount * termMonths - 10.0'
    expect(buildArithmetic(form)).toBe(expression)
    expect(parseArithmetic(expression, vars)).toEqual(form)
    expect(parseArithmetic('total / 2.5', vars)).toEqual({
      first: { kind: 'field', path: 'total' },
      rest: [{ op: '/', operand: { kind: 'number', value: '2.5' } }],
    })
  })

  it('leaves other expressions to the expression input', () => {
    expect(parseArithmetic('(termMonths + 1.0) * 2.0', vars)).toBeUndefined()
    expect(parseArithmetic('partnerName + "x"', vars)).toBeUndefined()
    expect(parseArithmetic('termMonths *', vars)).toBeUndefined()
  })
})

describe('placeholders and free expressions', () => {
  it('offers amounts by their parts and formats by type', () => {
    expect(placeholderPaths(field('monthlyFee', 'Amount')).map((p) => p.path)).toEqual([
      'monthlyFee',
      'monthlyFee.amount',
      'monthlyFee.currency',
    ])
    expect(formatsFor('Date')).toEqual(['plain', 'date:long', 'date:short'])
    expect(formatsFor('Text')).toEqual(['plain'])
  })

  it('checks brackets and strings before the server does', () => {
    expect(quickCheck('')).toBe('empty')
    expect(quickCheck('(a && b')).toBe('brackets')
    expect(quickCheck('a) (')).toBe('brackets')
    expect(quickCheck('name == "open')).toBe('string')
    expect(quickCheck('name == "(" && size(x) > 1')).toBeUndefined()
  })

  it('inserts a field at the caret', () => {
    expect(insertAtCaret('a > ', 4, 4, 'b')).toEqual({ text: 'a > b', caret: 5 })
    expect(insertAtCaret('x', 1, 1, 'y')).toEqual({ text: 'x y', caret: 3 })
    expect(insertAtCaret('', 0, 0, 'y')).toEqual({ text: 'y', caret: 1 })
  })
})
