import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { setLocale } from '@/i18n'
import { PdfDownload } from './pdf-download'

const version = (status: string): DocumentVersionView =>
  ({ id: 'v1', version: '1.0-draft.1', status, workingState: 1 }) as unknown as DocumentVersionView

function renderDownload(status: string, wordExport: boolean) {
  const client = {} as unknown as ApiClient
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApiClientProvider client={client}>
        <PdfDownload documentId="d1" version={version(status)} wordExport={wordExport} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  setLocale('en')
})

describe('PDF and Word download (DMS-121)', () => {
  it('offers the Word export where asked for (structured documents), in German', () => {
    setLocale('de')
    renderDownload('Draft', true)
    expect(screen.getByRole('button', { name: 'PDF herunterladen' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Als Word herunterladen' })).toBeInTheDocument()
  })

  it('file documents get no Word export', () => {
    renderDownload('Draft', false)
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download as Word' })).not.toBeInTheDocument()
  })

  it('a withdrawn draft has neither download', () => {
    renderDownload('Withdrawn', true)
    expect(screen.queryByRole('button', { name: 'Download as Word' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download PDF' })).not.toBeInTheDocument()
  })
})
