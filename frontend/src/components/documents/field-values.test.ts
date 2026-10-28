import { describe, expect, it } from 'vitest'
import type { FieldType, TemplateField } from '@/api/templates'
import { collectValues, convert, initialInputs, parseNumber, type FormInputs } from './field-values'

function field(
  key: string,
  fieldType: FieldType,
  extra: Partial<TemplateField> = {},
): TemplateField {
  return {
    key,
    label: key,
    fieldType,
    multiple: false,
    required: false,
    usedInTemplate: true,
    ...extra,
  }
}

describe('field values', () => {
  it('converts every field type to a metadata value', () => {
    expect(convert(field('t', { type: 'Text' }), ' ACME ')).toEqual({
      kind: 'value',
      value: { type: 'Text', value: 'ACME' },
    })
    expect(convert(field('n', { type: 'Number' }), '12,5')).toEqual({
      kind: 'value',
      value: { type: 'Number', value: 12.5 },
    })
    expect(convert(field('a', { type: 'Amount' }), { amount: '1250.50', currency: 'eur' })).toEqual(
      { kind: 'value', value: { type: 'Amount', value: 1250.5, currency: 'EUR' } },
    )
    expect(convert(field('d', { type: 'Date' }), '2027-01-31')).toEqual({
      kind: 'value',
      value: { type: 'Date', value: '2027-01-31' },
    })
    expect(
      convert(
        field('s', {
          type: 'Selection',
          options: [{ code: 'fixed', label: 'Fixed term', active: true }],
        }),
        'fixed',
      ),
    ).toEqual({ kind: 'value', value: { type: 'Selection', code: 'fixed' } })
    expect(convert(field('y', { type: 'YesNo' }), 'false')).toEqual({
      kind: 'value',
      value: { type: 'YesNo', value: false },
    })
    expect(convert(field('p', { type: 'PersonRef' }), 'legal')).toEqual({
      kind: 'value',
      value: { type: 'PersonRef', userId: 'legal' },
    })
  })

  it('converts multiple values: lines of text and selected codes', () => {
    expect(
      convert(field('tags', { type: 'Text' }, { multiple: true }), 'Hosting\n\nSupport\n'),
    ).toEqual({
      kind: 'value',
      value: {
        type: 'Many',
        values: [
          { type: 'Text', value: 'Hosting' },
          { type: 'Text', value: 'Support' },
        ],
      },
    })
    const options = [
      { code: 'a', label: 'A', active: true },
      { code: 'b', label: 'B', active: true },
    ]
    expect(convert(field('s', { type: 'Selection', options }, { multiple: true }), ['b'])).toEqual({
      kind: 'value',
      value: { type: 'Many', values: [{ type: 'Selection', code: 'b' }] },
    })
    expect(convert(field('s', { type: 'Selection', options }, { multiple: true }), [])).toEqual({
      kind: 'empty',
    })
  })

  it('validates against the field rules', () => {
    expect(convert(field('n', { type: 'Number', min: 1, max: 120 }), '500')).toEqual({
      kind: 'invalid',
      message: 'Must be at most 120.',
    })
    expect(convert(field('n', { type: 'Number', scale: 2 }), '1.234')).toEqual({
      kind: 'invalid',
      message: 'At most 2 decimal places.',
    })
    expect(convert(field('n', { type: 'Number' }), '12a').kind).toBe('invalid')
    expect(convert(field('t', { type: 'Text', maxLength: 3 }), 'ACME').kind).toBe('invalid')
    expect(convert(field('t', { type: 'Text', pattern: '[a-z]{2}' }), 'en').kind).toBe('value')
    expect(convert(field('t', { type: 'Text', pattern: '[a-z]{2}' }), 'eng').kind).toBe('invalid')
    expect(
      convert(field('a', { type: 'Amount', currencies: ['EUR'] }), {
        amount: '10',
        currency: 'USD',
      }),
    ).toEqual({ kind: 'invalid', message: 'Allowed currencies: EUR.' })
    expect(convert(field('d', { type: 'Date', min: '2027-01-01' }), '2026-12-31').kind).toBe(
      'invalid',
    )
    expect(convert(field('r', { type: 'DocumentRef' }), 'not-a-uuid').kind).toBe('invalid')
    expect(convert(field('tags', { type: 'Number' }, { multiple: true }), '1\nx')).toEqual({
      kind: 'invalid',
      message: 'Value 2: Enter a number, e.g. 12 or 12.5.',
    })
  })

  it('treats blank input as empty and collects only valid values', () => {
    const fields = [
      field('name', { type: 'Text' }),
      field('fee', { type: 'Amount' }),
      field('months', { type: 'Number' }),
    ]
    const inputs: FormInputs = { ...initialInputs(fields), name: 'ACME', months: 'many' }
    expect(inputs.fee).toEqual({ amount: '', currency: 'EUR' })
    expect(collectValues(fields, inputs)).toEqual({
      values: { name: { type: 'Text', value: 'ACME' } },
      errors: { months: 'Enter a number, e.g. 12 or 12.5.' },
    })
    expect(parseNumber('  ')).toBeUndefined()
  })
})
