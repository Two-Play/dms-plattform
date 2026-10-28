import { describe, expect, it } from 'vitest'
import { snippetParts } from './snippet'

describe('snippetParts', () => {
  it('splits a snippet at its highlights', () => {
    expect(snippetParts('Wear <mark>gloves</mark> in the <mark>lab</mark>.')).toEqual([
      { text: 'Wear ', highlighted: false },
      { text: 'gloves', highlighted: true },
      { text: ' in the ', highlighted: false },
      { text: 'lab', highlighted: true },
      { text: '.', highlighted: false },
    ])
  })

  it('keeps every other markup as literal text', () => {
    expect(snippetParts('<b>bold</b> <img src=x onerror=alert(1)> <mark>hit</mark>')).toEqual([
      { text: '<b>bold</b> <img src=x onerror=alert(1)> ', highlighted: false },
      { text: 'hit', highlighted: true },
    ])
  })

  it('tolerates unbalanced and upper-case tags', () => {
    expect(snippetParts('a <MARK>b</MARK> c <mark>d')).toEqual([
      { text: 'a ', highlighted: false },
      { text: 'b', highlighted: true },
      { text: ' c ', highlighted: false },
      { text: 'd', highlighted: true },
    ])
    expect(snippetParts('</mark>plain')).toEqual([{ text: 'plain', highlighted: false }])
    expect(snippetParts('')).toEqual([])
  })
})
