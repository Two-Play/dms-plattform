import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentView } from '@/api/partners'
import type { MetadataValue, TemplateField } from '@/api/templates'
import {
  allowedGroupsOf,
  groupKeys,
  isRestrictedInput,
  visibleFields,
  withoutHiddenGroups,
} from './access-groups'
import { AccessNote } from './document-page'
import { DocumentWorkspace } from './document-workspace'

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'

const fields: TemplateField[] = [
  {
    key: 'title',
    label: 'Title',
    fieldType: { type: 'Text' },
    multiple: false,
    required: true,
    usedInTemplate: false,
  },
  {
    key: 'confidentiality',
    label: 'Confidentiality',
    fieldType: {
      type: 'Selection',
      options: [
        { code: 'Internal', label: 'Internal', active: true },
        { code: 'Confidential', label: 'Confidential', active: true },
      ],
    },
    multiple: false,
    required: true,
    usedInTemplate: false,
  },
  {
    key: 'allowedGroups',
    label: 'Allowed groups',
    fieldType: { type: 'Text', maxLength: 63, pattern: '[a-z0-9][a-z0-9-]{0,62}' },
    multiple: true,
    required: false,
    usedInTemplate: false,
  },
]

const groups = [
  { key: 'hr', name: 'HR' },
  { key: 'management', name: 'Management' },
]

interface Body {
  values: Record<string, MetadataValue>
}

function renderWorkspace() {
  const get = vi.fn((path: string) =>
    Promise.resolve({
      data: path === '/api/v1/groups' ? groups : undefined,
      response: new Response(null, { status: 200 }),
    }),
  )
  const post = vi.fn((path: string, init: { body: Body }) =>
    Promise.resolve({
      data:
        path === '/api/v1/documents'
          ? { id: 'd1', current: { version: '1.0-draft.1' } }
          : { html: '<article></article>', missingRequired: [], issues: [] },
      response: new Response(null, { status: path === '/api/v1/documents' ? 201 : 200 }),
      body: init.body,
    }),
  )
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <DocumentWorkspace
          templateId={templateId}
          version={1}
          language="en"
          fields={fields}
          references={[]}
        />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  const created = () =>
    post.mock.calls.find(([path]) => path === '/api/v1/documents')?.[1].body.values
  return { get, created }
}

describe('access groups of confidential documents (DMS-101)', () => {
  it('converts inputs and stored values', () => {
    expect(isRestrictedInput('Confidential')).toBe(true)
    expect(isRestrictedInput('StrictlyConfidential')).toBe(true)
    expect(isRestrictedInput('Internal')).toBe(false)
    expect(groupKeys('hr\n\nmanagement\n')).toEqual(['hr', 'management'])
    expect(
      allowedGroupsOf({
        allowedGroups: { type: 'Many', values: [{ type: 'Text', value: 'hr' }] },
      }),
    ).toEqual(['hr'])
    expect(visibleFields(fields, { confidentiality: 'Internal' }).map((f) => f.key)).toEqual([
      'title',
      'confidentiality',
    ])
    expect(
      visibleFields(fields, { confidentiality: 'Confidential' }).find(
        (f) => f.key === 'allowedGroups',
      )?.required,
    ).toBe(true)
    expect(withoutHiddenGroups({ title: 1, allowedGroups: 2 }, false)).toEqual({ title: 1 })
    expect(withoutHiddenGroups({ title: 1, allowedGroups: 2 }, true)).toEqual({
      title: 1,
      allowedGroups: 2,
    })
  })

  it('offers the group picker once the document is confidential and saves the picked groups', async () => {
    const { get, created } = renderWorkspace()
    const user = userEvent.setup()

    expect(screen.queryByText('Allowed groups')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/^Title/), 'Board minutes')
    await user.selectOptions(screen.getByLabelText(/^Confidentiality/), 'Confidential')

    const management = await screen.findByRole('checkbox', { name: 'Management' })
    expect(screen.getByRole('checkbox', { name: /HR$/ })).not.toBeChecked()
    expect(get).toHaveBeenCalledWith('/api/v1/groups')
    await user.click(management)
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      expect(created()).toEqual({
        title: { type: 'Text', value: 'Board minutes' },
        confidentiality: { type: 'Selection', code: 'Confidential' },
        allowedGroups: { type: 'Many', values: [{ type: 'Text', value: 'management' }] },
      })
    })
  })

  it('does not send the groups of a document that is no longer confidential', async () => {
    const { created } = renderWorkspace()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/^Title/), 'Policy')
    await user.selectOptions(screen.getByLabelText(/^Confidentiality/), 'Confidential')
    await user.click(await screen.findByRole('checkbox', { name: /HR$/ }))
    await user.selectOptions(screen.getByLabelText(/^Confidentiality/), 'Internal')
    expect(screen.queryByRole('checkbox', { name: /HR$/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => {
      expect(created()).toEqual({
        title: { type: 'Text', value: 'Policy' },
        confidentiality: { type: 'Selection', code: 'Internal' },
      })
    })
  })

  it('names the groups that may see a confidential document', async () => {
    const get = vi.fn(() =>
      Promise.resolve({ data: groups, response: new Response(null, { status: 200 }) }),
    )
    const client = { GET: get } as unknown as ApiClient
    const document = {
      current: {
        values: {
          confidentiality: {
            value: { type: 'Selection', code: 'StrictlyConfidential' },
            origin: 'Manual',
          },
          allowedGroups: {
            value: {
              type: 'Many',
              values: [
                { type: 'Text', value: 'management' },
                { type: 'Text', value: 'legacy' },
              ],
            },
            origin: 'Manual',
          },
        },
      },
    } as unknown as DocumentView
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ApiClientProvider client={client}>
          <AccessNote document={document} />
        </ApiClientProvider>
      </QueryClientProvider>,
    )

    expect(
      await screen.findByText(/visible to the author, the owner and members of Management, legacy/),
    ).toBeInTheDocument()
    expect(screen.getByText('Strictly confidential:')).toBeInTheDocument()
  })
})
