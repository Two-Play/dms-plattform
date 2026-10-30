import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import type { UploadFile, WordFields } from '@/api/word'
import { setLocale } from '@/i18n'
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

const typedFields = (editable: boolean, values: Record<string, string> = {}): WordFields => ({
  versionId,
  version: '1.0-draft.2',
  status: editable ? 'Draft' : 'Published',
  editable,
  fields: [
    {
      key: 'agreed',
      label: 'Agreed',
      type: 'Checkbox',
      required: false,
      source: 'Manual',
      value: values.agreed,
    },
    {
      key: 'priority',
      label: 'Priority',
      type: 'Choice',
      required: false,
      source: 'Manual',
      options: ['High', 'Low'],
      value: values.priority,
    },
    {
      key: 'positions',
      label: 'Positions',
      type: 'Rows',
      required: true,
      source: 'Manual',
      columns: [
        { key: 'item', label: 'Item', type: 'Text', required: true },
        { key: 'due', label: 'Due', type: 'Date', required: false },
        { key: 'ok', label: 'Checked', type: 'Checkbox', required: false },
      ],
      value: values.positions,
      warning: 'nested repeating sections are not supported',
    },
  ],
})

function renderPanel(
  v: DocumentVersionView,
  editable: boolean,
  upload?: UploadFile<DocumentVersionView>,
  wordFields: WordFields = fields(editable),
  roles: string[] = ['Author'],
) {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/me') return ok({ userId: 'u-1', tenantId: 't-1', roles })
    if (path === '/api/v1/users') return ok([{ id: 'author-1', name: 'Ada Author' }])
    if (path.endsWith('/word-fields')) return ok(wordFields)
    return ok(new Blob(['%PDF-1.7']))
  })
  const put = vi.fn(() => ok(v))
  const post = vi.fn(() =>
    ok(
      { templateId: 'tpl-9', version: 1, templateName: 'Wartungsvertrag Muster', status: 'Draft' },
      201,
    ),
  )
  const client = { GET: get, PUT: put, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <FileContentPanel documentId={documentId} version={v} editable={editable} upload={upload} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, put, post, user: userEvent.setup({ applyAccept: false }) }
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

  it('typed fields: check box, choice and rows that can be added and removed (DMS-122)', async () => {
    const { put, user } = renderPanel(
      version('Draft'),
      true,
      undefined,
      typedFields(true, { positions: JSON.stringify([{ item: 'Pump' }]) }),
    )

    const agreed = await screen.findByRole('checkbox', { name: /Agreed/ })
    expect(agreed).not.toBeChecked()
    await user.click(agreed)
    const priority = screen.getByRole('combobox', { name: /Priority/ })
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Choose…',
      'High',
      'Low',
    ])
    await user.selectOptions(priority, 'Low')
    expect(
      screen.getByText(/Not filled as shown in the file: nested repeating sections/),
    ).toBeInTheDocument()

    const rows = screen.getByRole('group', { name: 'Positions' })
    expect(screen.getByRole('textbox', { name: 'Item, row 1' })).toHaveValue('Pump')
    await user.click(screen.getByRole('button', { name: 'Add row' }))
    await user.type(screen.getByRole('textbox', { name: 'Item, row 2' }), 'Valve')
    await user.click(screen.getByRole('checkbox', { name: 'Checked, row 2' }))
    expect(rows.querySelectorAll('input[type="date"]')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Add row' }))
    await user.click(screen.getByRole('button', { name: 'Remove row 1' }))
    expect(screen.getByRole('textbox', { name: 'Item, row 1' })).toHaveValue('Valve')
    await user.click(screen.getByRole('button', { name: 'Save field values' }))

    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/word-fields', {
        params: { path: { id: documentId } },
        body: {
          values: {
            agreed: 'true',
            priority: 'Low',
            positions: JSON.stringify([{ item: 'Valve', ok: 'true' }, {}]),
          },
        },
      })
    })
  })

  it('shows typed values read-only and speaks German', async () => {
    setLocale('de')
    renderPanel(
      version('Published', 'Budget.xlsx'),
      false,
      undefined,
      typedFields(false, {
        agreed: 'true',
        priority: 'High',
        positions: JSON.stringify([{ item: 'Pumpe', due: '2027-01-31', ok: 'false' }]),
      }),
    )

    expect(await screen.findByText('Ja')).toBeInTheDocument()
    expect(screen.getByText('High')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Positions' })).toBeInTheDocument()
    expect(screen.getByText('Pumpe')).toBeInTheDocument()
    expect(screen.getByText('31.01.2027')).toBeInTheDocument()
    expect(screen.getByText('Nein')).toBeInTheDocument()
    // an Excel file has fields and a filled download, but no Word download
    expect(screen.getByRole('button', { name: 'Befüllte Datei herunterladen' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('German labels of the inputs for rows', async () => {
    setLocale('de')
    const { user } = renderPanel(version('Draft'), true, undefined, typedFields(true))
    expect(await screen.findByText('Noch keine Zeilen.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Zeile hinzufügen' }))
    expect(screen.getByRole('textbox', { name: 'Item, Zeile 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Zeile 1 entfernen' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Bitte wählen …' })).toBeInTheDocument()
  })

  it('designers use a Word document as template, without its values (DMS-127)', async () => {
    const { post, user } = renderPanel(version('Published'), false, undefined, fields(false), [
      'TemplateDesigner',
    ])
    await user.click(await screen.findByRole('button', { name: 'Use as template…' }))
    const name = screen.getByLabelText('Name of the template')
    expect(name).toHaveValue('Wartungsvertrag')
    await user.clear(name)
    await user.type(name, 'Wartungsvertrag Muster')
    await user.click(screen.getByRole('button', { name: 'Create template' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(
        '/api/v1/documents/{id}/versions/{versionId}/word-template',
        {
          params: { path: { id: documentId, versionId } },
          body: { name: 'Wartungsvertrag Muster', description: undefined },
        },
      )
    })
    expect(await screen.findByRole('link', { name: 'Open the template' })).toHaveAttribute(
      'href',
      '/templates/tpl-9',
    )
  })

  it('only designers see "Use as template", and only for Word documents', async () => {
    renderPanel(version('Published'), false, undefined, fields(false), ['Author'])
    expect(await screen.findByText('VT-2026-0007')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use as template…' })).not.toBeInTheDocument()
  })

  it('no "Use as template" for other files', async () => {
    renderPanel(version('Published', 'Budget.xlsx'), false, undefined, fields(false), [
      'TemplateDesigner',
    ])
    expect(await screen.findByText('VT-2026-0007')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use as template…' })).not.toBeInTheDocument()
  })

  it('"Use as template" speaks German', async () => {
    setLocale('de')
    const { user } = renderPanel(version('Published'), false, undefined, fields(false), [
      'TemplateDesigner',
    ])
    await user.click(await screen.findByRole('button', { name: 'Als Vorlage verwenden …' }))
    expect(screen.getByLabelText('Name der Vorlage')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Vorlage anlegen' })).toBeInTheDocument()
  })
})
