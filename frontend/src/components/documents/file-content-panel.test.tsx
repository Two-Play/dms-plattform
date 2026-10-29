import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import type { UploadFile, WordFields } from '@/api/word'
import { FileContentPanel } from './file-content-panel'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d2'
const versionId = '7c1e2f0a-0000-4000-8000-0000000000v2'

function version(status: string, fileName = 'Wartungsvertrag.docx'): DocumentVersionView {
  return {
    id: versionId,
    version: status === 'Draft' ? '1.0-draft.2' : '1.0',
    workingState: 2,
    status,
    changeKind: 'Major',
    scope: '',
    classVersion: 1,
    templateId: 't1',
    templateVersion: 1,
    content: { sections: [] },
    values: {},
    clauses: [],
    createdBy: 'author-1',
    createdAt: '2026-10-29T09:00:00Z',
    updatedAt: '2026-10-29T09:00:00Z',
    requiresExtraReview: false,
    requiresAcknowledgement: true,
    releasedAt: status === 'Draft' ? undefined : '2026-10-29T10:00:00Z',
    file: {
      versionId,
      name: fileName,
      contentType: 'application/octet-stream',
      size: 2048,
      sha256: 'ef'.repeat(32),
      scanStatus: 'NotScanned',
      uploadedBy: 'author-1',
      uploadedAt: '2026-10-29T09:00:00Z',
      downloadUrl: `/api/v1/documents/${documentId}/versions/${versionId}/file`,
      previewUrl: `/api/v1/documents/${documentId}/versions/${versionId}/preview.pdf`,
    },
  }
}

const fields = (editable: boolean): WordFields => ({
  versionId,
  version: '1.0-draft.2',
  status: editable ? 'Draft' : 'Published',
  editable,
  fields: [
    {
      key: 'documentNumber',
      label: 'Dokumentnummer',
      type: 'Text',
      required: false,
      source: 'Automatic',
      value: 'VT-2026-0007',
    },
    { key: 'customer', label: 'Kunde', type: 'Text', required: true, source: 'Manual' },
    {
      key: 'deliveryDate',
      label: 'Liefertermin',
      type: 'Date',
      required: false,
      source: 'Manual',
      value: '2027-01-31',
    },
  ],
})

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function renderPanel(
  v: DocumentVersionView,
  editable: boolean,
  upload?: UploadFile<DocumentVersionView>,
) {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/users') return ok([{ id: 'author-1', name: 'Ada Author' }])
    if (path.endsWith('/word-fields')) return ok(fields(editable))
    return ok(new Blob(['%PDF-1.7']))
  })
  const put = vi.fn(() => ok(v))
  const client = { GET: get, PUT: put } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <FileContentPanel documentId={documentId} version={v} editable={editable} upload={upload} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, put, user: userEvent.setup({ applyAccept: false }) }
}

describe('Content of a file document (DMS-115)', () => {
  it('shows the original and the Word fields; the author enters the manual fields', async () => {
    const { put, user } = renderPanel(version('Draft'), true)

    expect(screen.getByText('Wartungsvertrag.docx')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download original' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download Word (filled)' })).toBeInTheDocument()
    // automatic fields are shown, not entered
    expect(await screen.findByText('VT-2026-0007')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /Dokumentnummer/ })).not.toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: /Kunde/ }), 'ACME GmbH')
    expect(screen.getByLabelText(/Liefertermin/)).toHaveValue('2027-01-31')
    await user.click(screen.getByRole('button', { name: 'Save field values' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/word-fields', {
        params: { path: { id: documentId } },
        body: { values: { customer: 'ACME GmbH', deliveryDate: '2027-01-31' } },
      })
    })
    expect(screen.getByText('How to mark fields in Word')).toBeInTheDocument()
  })

  it('uploads a new file only of a convertible type', async () => {
    const upload = vi.fn<UploadFile<DocumentVersionView>>(() => Promise.resolve(version('Draft')))
    const { user } = renderPanel(version('Draft'), true, upload)
    const input = screen.getByLabelText('Replace file…', { selector: 'input' })

    await user.upload(input, new File(['x'], 'archive.zip'))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Files of type .zip cannot be archived as PDF/A.',
    )
    expect(upload).not.toHaveBeenCalled()

    await user.upload(input, new File(['x'], 'Vertrag v2.docx'))
    await waitFor(() => {
      expect(upload).toHaveBeenCalledTimes(1)
    })
  })

  it('an approved version is read-only and loads its PDF preview on request', async () => {
    const { get, user } = renderPanel(version('Published', 'Plan.pdf'), false)

    expect(screen.queryByRole('button', { name: /Replace file/ })).not.toBeInTheDocument()
    // not a Word file: no fields, no filled Word document
    expect(screen.queryByRole('button', { name: 'Download Word (filled)' })).not.toBeInTheDocument()
    expect(screen.getByText('The archived PDF/A of the approved version.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show preview' }))
    await waitFor(() => {
      expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/preview.pdf', {
        params: { path: { id: documentId, versionId } },
        parseAs: 'blob',
      })
    })
  })

  it('the fields of a frozen version are shown read-only', async () => {
    renderPanel(version('Published'), false)
    expect(
      await screen.findByText('The field values are frozen with the version.'),
    ).toBeInTheDocument()
    expect(await screen.findByText('VT-2026-0007')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save field values' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /Kunde/ })).not.toBeInTheDocument()
  })
})
