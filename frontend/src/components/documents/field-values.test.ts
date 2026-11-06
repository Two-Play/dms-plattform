import { describe, expect, it } from 'vitest'
import type { FieldType, TemplateField } from '@/api/templates'
import { setLocale } from '@/i18n'
import { collectValues, convert, initialInputs, type FormInputs } from './field-values'

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
    expect(convert(field('n', { type: 'Number' }), '12.5')).toEqual({
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
  })

  describe('numbers in the German UI (DMS-165)', () => {
    const number = field('n', { type: 'Number' })
    const amount = field('a', { type: 'Amount' })

    it('reads the dot as grouping and the comma as decimal separator', () => {
      setLocale('de')
      expect(convert(number, '1.250')).toEqual({
        kind: 'value',
        value: { type: 'Number', value: 1250 },
      })
      expect(convert(amount, { amount: '1.250,50', currency: 'EUR' })).toEqual({
        kind: 'value',
        value: { type: 'Amount', value: 1250.5, currency: 'EUR' },
      })
      expect(convert(amount, { amount: '1250,5', currency: 'EUR' })).toEqual({
        kind: 'value',
        value: { type: 'Amount', value: 1250.5, currency: 'EUR' },
      })
    })

    it('refuses forms it would have to guess, with a message that explains the notation', () => {
      setLocale('de')
      const message =
        'Unklare Schreibweise: Das Komma trennt die Dezimalstellen, der Punkt nur Dreiergruppen, z. B. 1.250,50.'
      expect(convert(number, '1.25')).toEqual({ kind: 'invalid', message })
      expect(convert(amount, { amount: '1.25', currency: 'EUR' })).toEqual({
        kind: 'invalid',
        message,
      })
      expect(convert(field('m', { type: 'Number' }, { multiple: true }), '1\n1.25')).toEqual({
        kind: 'invalid',
        message: `Wert 2: ${message}`,
      })
    })

    it('counts decimals after the comma, not digits after a group separator', () => {
      setLocale('de')
      const scaled = field('n', { type: 'Number', scale: 2 })
      expect(convert(scaled, '1.250').kind).toBe('value')
      expect(convert(scaled, '1.250,50').kind).toBe('value')
      expect(convert(scaled, '1,234').kind).toBe('invalid')
    })
  })

  describe('multiple amounts (DMS-168)', () => {
    const fees = field('fees', { type: 'Amount' }, { multiple: true })
    const swiss = field('fees', { type: 'Amount', currencies: ['CHF'] }, { multiple: true })
    const two = field('fees', { type: 'Amount', currencies: ['EUR', 'CHF'] }, { multiple: true })
    const amount = (value: number, currency: string) =>
      ({ type: 'Amount', value, currency }) as const

    it('reads one amount with its currency per line', () => {
      expect(convert(fees, '1,250.50 EUR\n3 chf')).toEqual({
        kind: 'value',
        value: { type: 'Many', values: [amount(1250.5, 'EUR'), amount(3, 'CHF')] },
      })
      setLocale('de')
      expect(convert(fees, '1.250,50 EUR')).toEqual({
        kind: 'value',
        value: { type: 'Many', values: [amount(1250.5, 'EUR')] },
      })
    })

    it('takes the currency of a line without one only when no other is possible', () => {
      expect(convert(swiss, '12.50')).toEqual({
        kind: 'value',
        value: { type: 'Many', values: [amount(12.5, 'CHF')] },
      })
      expect(convert(two, '12.50')).toEqual({
        kind: 'invalid',
        message: 'Value 1: Add the currency, e.g. 12.5 EUR.',
      })
      expect(convert(fees, '12.50')).toEqual({
        kind: 'invalid',
        message: 'Value 1: Add the currency, e.g. 12.5 EUR.',
      })
    })

    it('applies the rules of a single amount to every line', () => {
      expect(convert(two, '1 EUR\n2 USD')).toEqual({
        kind: 'invalid',
        message: 'Value 2: Allowed currencies: EUR, CHF.',
      })
      expect(convert(fees, 'abc EUR').kind).toBe('invalid')
      expect(convert(fees, 'abc').kind).toBe('invalid')
      expect(
        convert(field('f', { type: 'Amount', min: 10 }, { multiple: true }), '5 EUR'),
      ).toMatchObject({ kind: 'invalid', message: 'Value 1: Must be at least 10.' })
    })
  })

  it('names the limits of a field with all their digits (DMS-168)', () => {
    expect(convert(field('n', { type: 'Number', min: 0.12345 }), '0.1')).toEqual({
      kind: 'invalid',
      message: 'Must be at least 0.12345.',
    })
  })

  it('mirrors the rules in the English UI (DMS-165)', () => {
    const number = field('n', { type: 'Number' })
    expect(convert(number, '1,250.50')).toEqual({
      kind: 'value',
      value: { type: 'Number', value: 1250.5 },
    })
    expect(convert(number, '1,25')).toEqual({
      kind: 'invalid',
      message:
        'Unclear notation: the point separates decimals, the comma only groups of three, e.g. 1,250.50.',
    })
  })
})
