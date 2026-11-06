import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { setLocale } from '@/i18n'
import { PdfDownload } from './pdf-download'

const version = (status: string): DocumentVersionView =>
  ({ id: 'v1', version: '1.0-draft.1', status, workingState: 1 }) as unknown as DocumentVersionView

function renderDownload(status: string, wordExport: boolean, client = {} as unknown as ApiClient) {
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

/** A client whose draft PDF request is answered with this status, body and headers. */
function answering(status: number, body: unknown, headers: Record<string, string>): ApiClient {
  return {
    GET: vi
      .fn()
      .mockResolvedValue({ error: body, response: new Response(null, { status, headers }) }),
  } as unknown as ApiClient
}

const limited = { code: 'pdf_limit_reached', message: 'You have requested too many PDFs.' }

describe('a download over the limit (DMS-118)', () => {
  it('says when to try again, in German', async () => {
    setLocale('de')
    renderDownload('Draft', false, answering(429, limited, { 'Retry-After': '12' }))
    await userEvent.click(screen.getByRole('button', { name: 'PDF herunterladen' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Gerade werden zu viele PDFs erzeugt. Bitte versuchen Sie es in 12 s erneut.',
    )
  })

  it('says when to try again, in English', async () => {
    renderDownload('Draft', false, answering(429, limited, { 'Retry-After': '12' }))
    await userEvent.click(screen.getByRole('button', { name: 'Download PDF' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many PDFs are being generated right now. Please try again in 12 s.',
    )
  })

  it("shows the backend's message when no usable time came with the answer", async () => {
    renderDownload('Draft', false, answering(429, limited, { 'Retry-After': 'soon' }))
    await userEvent.click(screen.getByRole('button', { name: 'Download PDF' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The PDF could not be downloaded: You have requested too many PDFs.',
    )
  })
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
