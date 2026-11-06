import { describe, expect, it } from 'vitest'
import { canonicalToText, numberToText, parseNumberText } from './numbers'

describe('parseNumberText', () => {
  it.each([
    ['1.250', 1250],
    ['1.250,50', 1250.5],
    ['1250,5', 1250.5],
    ['1250', 1250],
    ['-1.250,5', -1250.5],
    ['1.250.000', 1250000],
    ['0,5', 0.5],
  ])('reads German %s as %d', (text, value) => {
    expect(parseNumberText(text, 'de')).toMatchObject({ kind: 'number', value })
  })

  it.each([
    ['1,250', 1250],
    ['1,250.50', 1250.5],
    ['1250.5', 1250.5],
    ['1.250', 1.25],
    ['-1,250.5', -1250.5],
  ])('reads English %s as %d', (text, value) => {
    expect(parseNumberText(text, 'en')).toMatchObject({ kind: 'number', value })
  })

  it.each(['1.25', '1250.5', '0.250', '012.250', '1.2500', '1.250.5', '1,250.50', '1,5,0', '5,'])(
    'refuses the German form %s instead of guessing',
    (text) => {
      expect(parseNumberText(text, 'de')).toEqual({ kind: 'ambiguous' })
    },
  )

  it.each(['1,25', '1250,5', '0,250', '1,2500', '1.250,50'])(
    'refuses the English form %s instead of guessing',
    (text) => {
      expect(parseNumberText(text, 'en')).toEqual({ kind: 'ambiguous' })
    },
  )

  it.each(['', ' ', 'abc', '12a', '1 250', '1e3', '+5'])('does not read %j as a number', (text) => {
    expect(parseNumberText(text, 'de')).toEqual({ kind: 'invalid' })
  })

  it('counts the decimals and gives the digits in the stored form', () => {
    expect(parseNumberText('1.250,50', 'de')).toEqual({
      kind: 'number',
      value: 1250.5,
      canonical: '1250.50',
      decimals: 2,
    })
    expect(parseNumberText('1,250', 'en')).toMatchObject({ canonical: '1250', decimals: 0 })
  })
})

describe('numberToText', () => {
  it('writes numbers in the format of the language', () => {
    expect(numberToText(1250.5, 'de')).toBe('1.250,5')
    expect(numberToText(1250.5, 'en')).toBe('1,250.5')
    expect(numberToText(-1250000, 'de')).toBe('-1.250.000')
    expect(numberToText(0.25, 'de')).toBe('0,25')
    expect(numberToText(12, 'de')).toBe('12')
  })

  it('agrees with Intl for the tags the UI uses', () => {
    for (const value of [1250.5, -1250000.25, 999, 1000]) {
      expect(numberToText(value, 'de')).toBe(
        new Intl.NumberFormat('de-DE', { maximumFractionDigits: 20 }).format(value),
      )
      expect(numberToText(value, 'en')).toBe(
        new Intl.NumberFormat('en-GB', { maximumFractionDigits: 20 }).format(value),
      )
    }
  })

  it.each([0, 1, 1250, 1250.5, -0.001, 1234567.891, 0.1 + 0.2, 1e21, 1e-7, 123456789012345680000])(
    'gives text that reads back as %d in both languages',
    (value) => {
      for (const locale of ['de', 'en'] as const) {
        expect(parseNumberText(numberToText(value, locale), locale)).toMatchObject({
          kind: 'number',
          value,
        })
      }
    },
  )
})

describe('canonicalToText', () => {
  it('keeps the digits of a stored number and only changes its separators', () => {
    expect(canonicalToText('1250.50', 'de')).toBe('1.250,50')
    expect(canonicalToText('1250.50', 'en')).toBe('1,250.50')
    expect(canonicalToText('-12', 'de')).toBe('-12')
  })
})
