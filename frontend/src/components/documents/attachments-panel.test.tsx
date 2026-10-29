import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Attachment, UploadAttachment, VersionAttachments } from '@/api/attachments'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import { setLocale } from '@/i18n'
import { AttachmentsPanel } from './attachments-panel'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const versionId = '7c1e2f0a-0000-4000-8000-0000000000v1'

const annex: Attachment = {
  id: '7c1e2f0a-0000-4000-8000-0000000000a1',
  versionId,
  name: 'Price list.xlsx',
  contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  size: 2048,
  sha256: 'ab'.repeat(32),
  scanStatus: 'NotScanned',
  addedBy: 'author-1',
  addedAt: '2026-10-28T09:00:00Z',
  downloadUrl: `/api/v1/documents/${documentId}/attachments/7c1e2f0a-0000-4000-8000-0000000000a1`,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function renderPanel(view: VersionAttachments, upload?: UploadAttachment) {
  let current = view
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/users') return ok([{ id: 'author-1', name: 'Ada Author' }])
    if (path === '/api/v1/documents/{id}/attachments/{attachmentId}') return ok(new Blob(['x']))
    return ok(current)
  })
  const patch = vi.fn(() => ok({ ...annex, name: 'Prices 2027.xlsx' }))
  const del = vi.fn(() => ok(undefined, 204))
  const client = { GET: get, PATCH: patch, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <AttachmentsPanel documentId={documentId} versionId={versionId} upload={upload} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return {
    get,
    patch,
    del,
    user: userEvent.setup(),
    setView: (next: VersionAttachments) => {
      current = next
    },
  }
}

const draft = (attachments: Attachment[]): VersionAttachments => ({
  versionId,
  version: '1.0-draft.1',
  status: 'Draft',
  editable: true,
  maxSizeBytes: 1024 * 1024,
  attachments,
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Attachments panel (DMS-114)', () => {
  it('uploads chosen files with progress, then lists them', async () => {
    let finish: (a: Attachment) => void = () => undefined
    const upload = vi.fn<UploadAttachment>(
      (_doc, _file, onProgress) =>
        new Promise<Attachment>((resolve) => {
          onProgress(512, 1024)
          finish = resolve
        }),
    )
    const { user, setView } = renderPanel(draft([]), upload)

    expect(await screen.findByText('No attachments')).toBeInTheDocument()
    expect(screen.getByText(/up to 1 MB/)).toBeInTheDocument()

    const file = new File(['x'.repeat(1024)], 'Annex 1.pdf', { type: 'application/pdf' })
    await user.upload(screen.getByTestId('attachment-input'), file)

    const progress = await screen.findByRole('progressbar', { name: 'Upload of Annex 1.pdf' })
    expect(progress).toHaveAttribute('value', '512')
    expect(upload).toHaveBeenCalledWith(documentId, file, expect.any(Function))

    setView(draft([{ ...annex, name: 'Annex 1.pdf', contentType: 'application/pdf' }]))
    finish({ ...annex, name: 'Annex 1.pdf' })

    const list = await screen.findByRole('list', { name: 'Attachments' })
    expect(within(list).getByText('Annex 1.pdf')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('refuses blocked types and oversized files before sending and shows backend refusals', async () => {
    const upload = vi.fn<UploadAttachment>(() =>
      Promise.reject(new ApiRequestError(422, 'found Eicar', 'attachment_infected')),
    )
    const { user } = renderPanel(draft([]), upload)
    await screen.findByText('No attachments')

    await user.upload(screen.getByTestId('attachment-input'), [
      new File(['MZ'], 'setup.exe'),
      new File(['x'.repeat(2 * 1024 * 1024)], 'huge.pdf'),
      new File(['X5O!P%@AP'], 'eicar.txt'),
    ])

    expect(await screen.findByText(/Files of type \.exe/)).toBeInTheDocument()
    expect(await screen.findByText('The file is larger than 1 MB.')).toBeInTheDocument()
    expect(await screen.findByText(/virus scanner found malware/)).toBeInTheDocument()
    expect(upload).toHaveBeenCalledTimes(1)
  })

  it('accepts files dropped on the drop zone', async () => {
    const upload = vi.fn<UploadAttachment>(() => Promise.resolve(annex))
    renderPanel(draft([]), upload)
    const zone = await screen.findByTestId('attachment-drop-zone')
    const file = new File(['data'], 'Minutes.docx')
    const dataTransfer = { files: [file] }
    zone.dispatchEvent(
      Object.assign(new Event('drop', { bubbles: true, cancelable: true }), { dataTransfer }),
    )
    await waitFor(() => {
      expect(upload).toHaveBeenCalledWith(documentId, file, expect.any(Function))
    })
  })

  it('renames and removes attachments of the draft', async () => {
    const { user, patch, del } = renderPanel(draft([annex]))

    await user.click(await screen.findByRole('button', { name: 'Rename Price list.xlsx' }))
    const field = screen.getByLabelText('New name')
    await user.clear(field)
    await user.type(field, 'Prices 2027.xlsx')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(patch).toHaveBeenCalledWith('/api/v1/documents/{id}/attachments/{attachmentId}', {
        params: { path: { id: documentId, attachmentId: annex.id } },
        body: { name: 'Prices 2027.xlsx' },
      })
    })

    await user.click(screen.getByRole('button', { name: 'Remove Price list.xlsx' }))
    await user.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => {
      expect(del).toHaveBeenCalledWith('/api/v1/documents/{id}/attachments/{attachmentId}', {
        params: { path: { id: documentId, attachmentId: annex.id } },
      })
    })
  })

  it('is read-only once the version is no longer a draft, and downloads attachments', async () => {
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:annex'), revokeObjectURL: vi.fn() })
    const downloads: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download)
    })
    const { user, get } = renderPanel({ ...draft([annex]), status: 'Published', editable: false })

    expect(await screen.findByText('Price list.xlsx')).toBeInTheDocument()
    expect(screen.queryByTestId('attachment-drop-zone')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Rename/ })).not.toBeInTheDocument()
    expect(screen.getByText(/frozen with the version/)).toBeInTheDocument()
    expect(screen.getByText(/2 kB/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Download Price list.xlsx' }))
    await waitFor(() => {
      expect(downloads).toEqual(['Price list.xlsx'])
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/attachments/{attachmentId}', {
      params: { path: { id: documentId, attachmentId: annex.id } },
      parseAs: 'blob',
    })
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel(draft([]))
    expect(await screen.findByText('Anlagen')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Dateien auswählen…' })).toBeInTheDocument()
    expect(screen.getByText('Keine Anlagen')).toBeInTheDocument()
  })
})
