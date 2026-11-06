import { describe, expect, it } from 'vitest'
import { attachmentName } from './search'

describe('attachmentName', () => {
  it('prefers the encoded name, which keeps umlauts (ADR 0072)', () => {
    expect(
      attachmentName(
        `attachment; filename="Vertrag (Zur_ckgezogen, nie g_ltig).docx"; filename*=UTF-8''Vertrag%20%28Zur%C3%BCckgezogen%2C%20nie%20g%C3%BCltig%29.docx`,
        'Vertrag.docx',
      ),
    ).toBe('Vertrag (Zurückgezogen, nie gültig).docx')
  })

  it('falls back to the plain name, then to the given one', () => {
    expect(attachmentName('attachment; filename="report.pdf"', 'x')).toBe('report.pdf')
    expect(attachmentName(`attachment; filename="plain.pdf"; filename*=UTF-8''%E0%A4%A`, 'x')).toBe(
      'plain.pdf',
    )
    expect(attachmentName(null, 'fallback.pdf')).toBe('fallback.pdf')
  })
})
