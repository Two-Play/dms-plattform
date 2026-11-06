import { describe, expect, it } from 'vitest'
import { t } from '@/i18n'
import { requestError } from './errors'

const failed = new Response(null, { status: 422 })

describe('requestError', () => {
  it('keeps the message of the backend', () => {
    const error = requestError(failed, { code: 'invalid_docx', message: 'broken' }, 'fallback')
    expect(error.message).toBe('broken')
    expect(error.code).toBe('invalid_docx')
  })

  it('explains a file refused for its size or structure in the language of the user', () => {
    const error = requestError(
      failed,
      { code: 'office_file_too_complex', message: 'more than 32 MiB of text and structure' },
      'fallback',
    )
    expect(error.message).toBe(t('upload.file.tooComplex'))
    expect(error.code).toBe('office_file_too_complex')
  })
})
