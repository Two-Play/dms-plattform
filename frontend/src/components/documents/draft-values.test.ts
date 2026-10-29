import { describe, expect, it } from 'vitest'
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

describe('displayValue', () => {
  it('formats stored values for reading', () => {
    expect(displayValue(stored.fee as MetadataValue)).toBe('1,250.5 CHF')
    expect(displayValue(stored.international as MetadataValue)).toBe('No')
    expect(displayValue(stored.tags as MetadataValue)).toBe('one, two')
  })
})
