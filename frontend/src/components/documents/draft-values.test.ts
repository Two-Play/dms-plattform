import { describe, expect, it } from 'vitest'
import { setLocale } from '@/i18n'
import type { FieldValue } from '@/api/documents'
import type { ReferenceField } from '@/api/partners'
import type { MetadataValue, TemplateField } from '@/api/templates'
import { displayValue, inputFromValue, inputsFromDraft, partnerFromDraft } from './draft-values'
import { convert } from './field-values'

function field(
  key: string,
  fieldType: TemplateField['fieldType'],
  multiple = false,
): TemplateField {
  return { key, label: key, fieldType, multiple, required: false, usedInTemplate: true }
}

const fields: TemplateField[] = [
  field('title', { type: 'Text' }),
  field('fee', { type: 'Amount', currencies: ['EUR', 'CHF'] }),
  field('term', { type: 'Number' }),
  field('start', { type: 'Date' }),
  field('kind', {
    type: 'Selection',
    options: [
      { code: 'a', label: 'A', active: true },
      { code: 'b', label: 'B', active: true },
    ],
  }),
  field('kinds', { type: 'Selection', options: [{ code: 'a', label: 'A', active: true }] }, true),
  field('tags', { type: 'Text' }, true),
  field('international', { type: 'YesNo' }),
  field('owner', { type: 'PersonRef' }),
]

const stored: Record<string, MetadataValue> = {
  title: { type: 'Text', value: 'Contract' },
  fee: { type: 'Amount', value: 1250.5, currency: 'CHF' },
  term: { type: 'Number', value: 36 },
  start: { type: 'Date', value: '2027-01-01' },
  kind: { type: 'Selection', code: 'b' },
  kinds: { type: 'Many', values: [{ type: 'Selection', code: 'a' }] },
  tags: {
    type: 'Many',
    values: [
      { type: 'Text', value: 'one' },
      { type: 'Text', value: 'two' },
    ],
  },
  international: { type: 'YesNo', value: false },
  owner: { type: 'PersonRef', userId: 'user-7' },
}

describe('inputFromValue', () => {
  it.each(fields.map((f) => [f.key, f] as const))(
    'turns a stored %s value into the input that converts back to it',
    (key, f) => {
      const value = stored[key]
      if (!value) throw new Error(`no value for ${key}`)
      expect(convert(f, inputFromValue(f, value))).toEqual({ kind: 'value', value })
    },
  )
})

describe('inputsFromDraft', () => {
  it('takes the entered values only, not computed, platform or partner master values', () => {
    const values: Record<string, FieldValue> = {
      title: { value: stored.title as MetadataValue, origin: 'Manual' },
      term: { value: { type: 'Number', value: 12 }, origin: 'Computed' },
      start: { value: { type: 'Date', value: '2027-02-01' }, origin: 'System', source: 'dms' },
    }
    const inputs = inputsFromDraft(fields, values)
    expect(inputs.title).toBe('Contract')
    expect(inputs.term).toBe('')
    expect(inputs.start).toBe('')
    expect(inputs.fee).toEqual({ amount: '', currency: 'EUR' })
  })
})

describe('partnerFromDraft', () => {
  const reference: ReferenceField = {
    key: 'contractPartner',
    label: 'Contract partner',
    source: 'partner-master',
    fields: [
      { attribute: 'id', field: 'partnerId' },
      { attribute: 'name', field: 'contractPartner' },
      { attribute: 'address', field: 'partnerAddress' },
    ],
  }

  it('rebuilds the taken-over partner from the values it filled', () => {
    const system = (value: string): FieldValue => ({
      value: { type: 'Text', value },
      origin: 'System',
      source: 'partner-master',
    })
    expect(
      partnerFromDraft(reference, {
        partnerId: system('P-10001'),
        contractPartner: system('Nordlicht Logistik GmbH'),
        partnerAddress: system('Hafenstraße 12, 20457 Hamburg, DE'),
      }),
    ).toMatchObject({
      id: 'P-10001',
      name: 'Nordlicht Logistik GmbH',
      address: 'Hafenstraße 12, 20457 Hamburg, DE',
    })
  })

  it('is undefined when no partner was taken over', () => {
    expect(
      partnerFromDraft(reference, {
        contractPartner: { value: { type: 'Text', value: 'typed' }, origin: 'Manual' },
      }),
    ).toBeUndefined()
  })
})

describe('numbers in the form (DMS-165)', () => {
  const number = field('term', { type: 'Number' })
  const amount = field('fee', { type: 'Amount' })
  const many: TemplateField = { ...number, multiple: true }

  it('shows stored numbers in the format of the UI language', () => {
    expect(inputFromValue(amount, stored.fee as MetadataValue)).toEqual({
      amount: '1,250.5',
      currency: 'CHF',
    })
    setLocale('de')
    expect(inputFromValue(amount, stored.fee as MetadataValue)).toEqual({
      amount: '1.250,5',
      currency: 'CHF',
    })
    expect(inputFromValue(number, { type: 'Number', value: 1250 })).toBe('1.250')
    expect(
      inputFromValue(many, {
        type: 'Many',
        values: [
          { type: 'Number', value: 1250.5 },
          { type: 'Number', value: 2 },
        ],
      }),
    ).toBe('1.250,5\n2')
  })

  it.each(['de', 'en'] as const)('returns the same values untouched in %s', (locale) => {
    setLocale(locale)
    for (const value of [0.25, 1.25, 1250, 1250.5, 1250000.75, -3.5]) {
      const stays = { type: 'Number', value } as const
      expect(convert(number, inputFromValue(number, stays))).toEqual({
        kind: 'value',
        value: stays,
      })
      const sum = { type: 'Amount', value, currency: 'EUR' } as const
      expect(convert(amount, inputFromValue(amount, sum))).toEqual({ kind: 'value', value: sum })
    }
    const list: MetadataValue = {
      type: 'Many',
      values: [
        { type: 'Number', value: 1250.5 },
        { type: 'Number', value: 0.25 },
      ],
    }
    expect(convert(many, inputFromValue(many, list))).toEqual({ kind: 'value', value: list })
  })

  it.each(['de', 'en'] as const)('returns multiple amounts untouched in %s (DMS-168)', (locale) => {
    setLocale(locale)
    const fees: TemplateField = { ...amount, multiple: true }
    const list: MetadataValue = {
      type: 'Many',
      values: [
        { type: 'Amount', value: 1250.5, currency: 'EUR' },
        { type: 'Amount', value: -3, currency: 'CHF' },
      ],
    }
    const text = inputFromValue(fees, list)
    expect(text).toBe(locale === 'de' ? '1.250,5 EUR\n-3 CHF' : '1,250.5 EUR\n-3 CHF')
    expect(convert(fees, text)).toEqual({ kind: 'value', value: list })
  })
})

describe('displayValue', () => {
  it('formats stored values for reading', () => {
    expect(displayValue(stored.fee as MetadataValue)).toBe('1,250.5 CHF')
    expect(displayValue(stored.international as MetadataValue)).toBe('No')
    expect(displayValue(stored.tags as MetadataValue)).toBe('one, two')
  })

  it('shows every stored decimal (DMS-168)', () => {
    expect(displayValue({ type: 'Number', value: 1.2345 })).toBe('1.2345')
    expect(displayValue({ type: 'Amount', value: 1234.56789, currency: 'EUR' })).toBe(
      '1,234.56789 EUR',
    )
    setLocale('de')
    expect(displayValue({ type: 'Number', value: 1.2345 })).toBe('1,2345')
    expect(displayValue({ type: 'Number', value: 1250000.125 })).toBe('1.250.000,125')
    expect(displayValue({ type: 'Number', value: -0.00001 })).toBe('-0,00001')
  })
})
