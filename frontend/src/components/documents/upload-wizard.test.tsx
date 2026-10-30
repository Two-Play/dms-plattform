import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { TemplateFields } from '@/api/templates'
import type { UploadAnalysis } from '@/api/uploads'
import { setLocale } from '@/i18n'
import { UploadWizard } from './upload-wizard'

const classId = '7c1e2f0a-0000-4000-8000-0000000000c1'
const uploadTemplate = '7c1e2f0a-0000-4000-8000-0000000000c2'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d4'
const templateId = '7c1e2f0a-0000-4000-8000-0000000000e5'

const analysis: UploadAnalysis = {
  name: 'Vertrag.docx',
  size: 2048,
  sha256: 'ab'.repeat(32),
  word: true,
  fillable: true,
  filledBefore: true,
  fields: [
    {
      key: 'title',
      label: 'Titel',
      type: 'Text',
      required: false,
      source: 'Automatic',
      value: 'Vertrag',
      options: [],
      columns: [],
    },
    {
      key: 'documentNumber',
      label: 'Number',
      type: 'Text',
      required: false,
      source: 'Automatic',
      options: [],
      columns: [],
    },
    {
      key: 'customer',
      label: 'Customer',
      type: 'Text',
      required: true,
      source: 'Manual',
      value: 'ACME',
      options: [],
      columns: [],
    },
  ],
}

const fields: TemplateFields = {
  templateId: uploadTemplate,
  version: 1,
  name: 'Upload: Contract',
  documentClassId: classId,
  documentClassVersion: 3,
  fields: [
    {
      key: 'title',
      label: 'Title',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: false,
    },
  ],
  references: [],
}

const contractClass = {
  id: classId,
  key: 'contract',
  version: 3,
  label: 'Contract',
  path: ['Contract'],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function setup(roles: string[], uploaded: UploadAnalysis = analysis) {
  const get = vi.fn((path: string) => {
    switch (path) {
      case '/api/v1/me':
        return ok({ userId: 'u-1', tenantId: 't-1', roles })
      case '/api/v1/uploads/classes':
        return ok([contractClass])
      case '/api/v1/admin/template-classes':
        return ok([contractClass])
      default:
        return ok(fields)
    }
  })
  const post = vi.fn((path: string) => {
    switch (path) {
      case '/api/v1/uploads/fields':
        return ok(uploaded)
      case '/api/v1/uploads/preview.pdf':
        return ok(new Blob(['%PDF-1.7'], { type: 'application/pdf' }))
      case '/api/v1/uploads/classes/{classId}/template':
        return ok({
          templateId: uploadTemplate,
          version: 1,
          name: 'Upload: Contract',
          documentClassId: classId,
          documentClassVersion: 3,
        })
      case '/api/v1/admin/templates':
        return ok({ templateId, version: 1, name: 'Vertrag', status: 'Draft' }, 201)
      default:
        return ok({ id: documentId, current: { version: '1.0-draft.1' } }, 201)
    }
  })
  const put = vi.fn(() => ok({ id: 'v-1', version: '1.0-draft.3' }))
  const client = { GET: get, POST: post, PUT: put } as unknown as ApiClient
  const uploadFile = vi.fn(() => vi.fn(() => Promise.resolve({ id: 'v-1' })))
  const uploadTemplateFile = vi.fn(() => vi.fn(() => Promise.resolve({ templateId })))
  const onDocumentCreated = vi.fn()
  const onTemplateCreated = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <UploadWizard
          onDocumentCreated={onDocumentCreated}
          onTemplateCreated={onTemplateCreated}
          uploadFile={uploadFile as never}
          uploadTemplate={uploadTemplateFile as never}
        />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return {
    get,
    post,
    put,
    uploadFile,
    uploadTemplateFile,
    onDocumentCreated,
    onTemplateCreated,
    user: userEvent.setup(),
  }
}

const docx = () =>
  new File(['PK'], 'Vertrag.docx', {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  })

/** Steps 1 to 3: choose the file, change the manual field, look at the preview. */
async function throughPreview(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('File'), docx())
  const customer = await screen.findByLabelText(/Customer/)
  expect(customer).toHaveValue('ACME')
  await user.clear(customer)
  await user.type(customer, 'ACME GmbH')
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByRole('heading', { name: 'Check the preview' })
  await user.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByRole('heading', { name: 'Decide what the file becomes' })
}

describe('Upload document wizard (DMS-127)', () => {
  it('reads the fields, previews the filled file and creates a document of a class with it', async () => {
    const { post, put, uploadFile, onDocumentCreated, user } = setup(['Author'])
    const steps = screen.getByRole('navigation', { name: 'Steps' })
    expect(within(steps).getByText('File').closest('li')).toHaveAttribute('aria-current', 'step')

    await user.upload(screen.getByLabelText('File'), docx())
    await screen.findByRole('heading', { name: 'Fill the fields' })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/uploads/fields',
      expect.objectContaining({ params: { query: { language: 'en' } } }),
    )
    // automatic fields: the value known now, or when it is assigned
    expect(screen.getByText('Vertrag')).toBeInTheDocument()
    expect(screen.getByText('Assigned when the document is created')).toBeInTheDocument()
    expect(screen.getByText(/The platform filled this document before/)).toBeInTheDocument()
    expect(within(steps).getByText('Fields').closest('li')).toHaveAttribute('aria-current', 'step')
    await user.click(screen.getByRole('button', { name: /File/ }))
    await screen.findByRole('heading', { name: 'Choose the file' })

    await throughPreview(user)
    const preview = post.mock.calls.find(([path]) => path === '/api/v1/uploads/preview.pdf')
    expect(preview).toBeDefined()

    // the template choice is disabled with its reason
    expect(screen.getByRole('radio', { name: /A template/ })).toBeDisabled()
    expect(screen.getByText('Only template designers can create templates.')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /A document/ })).toBeChecked()

    await user.selectOptions(await screen.findByLabelText('Document class'), classId)
    await user.click(screen.getByRole('button', { name: 'Continue with the metadata' }))
    // the title of the fields step is proposed and can be changed (DMS-131)
    const title = await screen.findByLabelText(/Title/)
    expect(title).toHaveValue('Vertrag')
    await user.clear(title)
    await user.type(title, 'Vertrag ACME')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      expect(onDocumentCreated).toHaveBeenCalledWith(documentId)
    })
    expect(post).toHaveBeenCalledWith('/api/v1/uploads/classes/{classId}/template', {
      params: { path: { classId } },
    })
    expect(post).toHaveBeenCalledWith('/api/v1/documents', {
      body: expect.objectContaining({
        templateId: uploadTemplate,
        templateVersion: 1,
        title: 'Vertrag ACME',
        kind: 'File',
      }) as unknown,
    })
    expect(uploadFile).toHaveBeenCalledWith(documentId)
    expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/word-fields', {
      params: { path: { id: documentId } },
      body: { values: { customer: 'ACME GmbH' } },
    })
  })

  it('proposes the file name as title when the file has no title value (DMS-131)', async () => {
    const withoutTitle: UploadAnalysis = {
      ...analysis,
      fields: (analysis.fields ?? []).map((f) =>
        f.key === 'title' ? { ...f, value: undefined } : f,
      ),
    }
    const { user } = setup(['Author'], withoutTitle)
    await throughPreview(user)
    await user.selectOptions(await screen.findByLabelText('Document class'), classId)
    await user.click(screen.getByRole('button', { name: 'Continue with the metadata' }))
    expect(await screen.findByLabelText(/Title/)).toHaveValue('Vertrag')
  })

  it('designers make a Word template of the file; the document choice is disabled', async () => {
    const { post, uploadTemplateFile, onTemplateCreated, user } = setup(['TemplateDesigner'])
    await throughPreview(user)
    expect(screen.getByRole('radio', { name: /A document/ })).toBeDisabled()
    expect(
      screen.getByText('Creating documents requires the author or owner role.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /A template/ })).toBeChecked()
    expect(screen.getByLabelText('Name of the template')).toHaveValue('Vertrag')
    await user.selectOptions(await screen.findByLabelText('Document class'), classId)
    await user.click(screen.getByRole('button', { name: 'Create template' }))

    await waitFor(() => {
      expect(onTemplateCreated).toHaveBeenCalledWith(templateId)
    })
    expect(post).toHaveBeenCalledWith('/api/v1/admin/templates', {
      body: {
        name: 'Vertrag',
        description: undefined,
        documentClassId: classId,
        documentClassVersion: 3,
        content: { sections: [] },
      },
    })
    expect(uploadTemplateFile).toHaveBeenCalledWith(templateId, 1)
  })

  it('reports files that cannot be used', async () => {
    const { post, user } = setup(['Author'])
    post.mockImplementationOnce(() =>
      Promise.resolve({
        data: undefined,
        error: { code: 'invalid_docx', message: 'the file is not a readable Word document' },
        response: new Response(null, { status: 422 }),
      }),
    )
    await user.upload(screen.getByLabelText('File'), docx())
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /The file cannot be used: the file is not a readable Word document/,
    )
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = setup(['Author', 'TemplateDesigner'])
    expect(screen.getByRole('heading', { name: 'Dokument hochladen' })).toBeInTheDocument()
    const steps = screen.getByRole('navigation', { name: 'Schritte' })
    expect(within(steps).getByText('Verwendung')).toBeInTheDocument()
    await user.upload(screen.getByLabelText('Datei'), docx())
    await screen.findByRole('heading', { name: 'Felder ausfüllen' })
    expect(screen.getByText('Wird beim Anlegen des Dokuments vergeben')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await screen.findByRole('heading', { name: 'Vorschau prüfen' })
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await screen.findByRole('heading', { name: 'Entscheiden, was aus der Datei wird' })
    expect(screen.getByRole('radio', { name: /Ein Dokument/ })).toBeEnabled()
    expect(screen.getByRole('radio', { name: /Eine Vorlage/ })).toBeEnabled()
  })
})
