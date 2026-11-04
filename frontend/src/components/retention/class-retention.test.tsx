import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import {
  classTree,
  type ClassRetention,
  type DocumentRetention,
  type RetentionRule,
} from '@/api/retention'
import { RetentionRow } from '@/components/documents/retention-row'
import { setLocale } from '@/i18n'
import { ClassRulesSection } from './class-rules-section'
import { ruleText } from './rule-text'

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })

const rule = (key: string, name: string, periodMonths: number): RetentionRule => ({
  key,
  name,
  periodMonths,
  trigger: 'EndOfValidity',
  fromYearEnd: false,
  updatedBy: 'records-1',
  updatedAt: '2026-10-30T08:00:00Z',
})

const policyRule = rule('policy-10y', 'Richtlinien', 120)
const shortRule = rule('short-18m', 'Kurz', 18)
const rules: RetentionRule[] = [rule('contract-10y', 'Verträge', 120), policyRule, shortRule]

const instruction: ClassRetention = {
  classId: 'c-instruction',
  classKey: 'central-instruction',
  label: 'Central instruction',
}

const classes: ClassRetention[] = [
  {
    classId: 'c-policy',
    classKey: 'policy',
    label: 'Policy',
    ruleKey: 'policy-10y',
    effectiveRule: 'policy-10y',
    updatedBy: 'records-1',
    updatedAt: '2026-10-30T08:00:00Z',
  },
  {
    classId: 'c-hr',
    classKey: 'hr-policy',
    label: 'HR policy',
    parentId: 'c-policy',
    effectiveRule: 'policy-10y',
    inheritedFrom: 'policy',
  },
  instruction,
]

function renderWith(client: ApiClient, children: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{children}</ApiClientProvider>
    </QueryClientProvider>,
  )
  return { invalidate, user: userEvent.setup() }
}

function classClient() {
  const get = vi.fn((path: string) =>
    path === '/api/v1/retention/class-rules' ? ok(classes) : ok(rules),
  )
  const put = vi.fn((_: string, init: { params: { path: { classKey: string } } }) =>
    ok({ ...instruction, classKey: init.params.path.classKey, ruleKey: 'short-18m' }),
  )
  return { client: { GET: get, PUT: put } as unknown as ApiClient, put }
}

const draft = {
  id: 'v-1',
  version: '1.0-draft.2',
  status: 'Draft',
} as unknown as DocumentVersionView

function retentionClient(retention: DocumentRetention) {
  const get = vi.fn(() => ok(retention))
  const put = vi.fn(() => ok({ ...retention, source: 'RecordsManagement' }))
  return { client: { GET: get, PUT: put } as unknown as ApiClient, get, put }
}

function renderRow(client: ApiClient, version: DocumentVersionView = draft) {
  return renderWith(
    client,
    <dl>
      <RetentionRow documentId="d-1" version={version} />
    </dl>,
  )
}

afterEach(() => {
  setLocale('en')
})

describe('retention rules of document classes (DMS-133)', () => {
  it('describes rules with name, period and start', () => {
    expect(ruleText(policyRule)).toBe('Richtlinien – 10 years from the end of validity')
    expect(ruleText({ ...shortRule, fromYearEnd: true, trigger: 'Approval' })).toBe(
      'Kurz – 18 months from approval, counted from the end of that year',
    )
    expect(ruleText({ key: 'old-key' })).toBe('old-key (unknown rule)')
    setLocale('de')
    expect(ruleText(policyRule)).toBe('Richtlinien – 10 Jahre ab Ende der Gültigkeit')
  })

  it('orders classes as a tree, parents before their subclasses', () => {
    expect(classTree(classes).map(({ cls, depth }) => [cls.classKey, depth])).toEqual([
      ['central-instruction', 0],
      ['policy', 0],
      ['hr-policy', 1],
    ])
  })

  it('shows own, inherited and missing rules and assigns a rule to a class', async () => {
    const { client, put } = classClient()
    const { invalidate, user } = renderWith(client, <ClassRulesSection />)

    const hr = await screen.findByTestId('class-rule-hr-policy')
    expect(within(hr).getByText('inherited from Policy')).toBeInTheDocument()
    expect(within(hr).getByRole('combobox')).toHaveValue('')
    const instruction = screen.getByTestId('class-rule-central-instruction')
    expect(within(instruction).getByText('No rule')).toBeInTheDocument()
    expect(within(instruction).getByText(/cannot be submitted/)).toBeInTheDocument()

    const save = within(instruction).getByRole('button', {
      name: 'Save the retention rule of Central instruction',
    })
    expect(save).toBeDisabled()
    await user.selectOptions(
      within(instruction).getByRole('combobox', { name: 'Retention rule of Central instruction' }),
      'short-18m',
    )
    await user.click(save)
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/retention/class-rules/{classKey}', {
        params: { path: { classKey: 'central-instruction' } },
        body: { rule: 'short-18m' },
      })
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['retention', 'class-rules'] })
  })

  it('is German', async () => {
    setLocale('de')
    const { client } = classClient()
    renderWith(client, <ClassRulesSection />)
    expect(
      await screen.findByRole('heading', { name: 'Aufbewahrungsregeln der Dokumentenklassen' }),
    ).toBeInTheDocument()
    const hr = await screen.findByTestId('class-rule-hr-policy')
    expect(within(hr).getByText('geerbt von Policy')).toBeInTheDocument()
    expect(
      within(hr).getByText('Richtlinien – 10 Jahre ab Ende der Gültigkeit', { selector: 'span' }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('option', { name: 'Keine (erben)' }).length).toBe(3)
    expect(screen.getByText('Keine Regel')).toBeInTheDocument()
  })
})

describe('retention rule in the metadata panel (DMS-133)', () => {
  const fromClass: DocumentRetention = {
    versionId: 'v-1',
    rule: { key: 'policy-10y', name: 'Richtlinien', periodMonths: 120, trigger: 'EndOfValidity' },
    source: 'Class',
    classRule: {
      key: 'policy-10y',
      name: 'Richtlinien',
      periodMonths: 120,
      trigger: 'EndOfValidity',
    },
    canChange: false,
    rules: [],
  }

  it('shows the rule of the version with name, period and its source', async () => {
    setLocale('de')
    const { client, get } = retentionClient(fromClass)
    renderRow(client)
    const cell = await screen.findByTestId('retention')
    await waitFor(() => {
      expect(cell).toHaveTextContent('Richtlinien – 10 Jahre ab Ende der Gültigkeit')
    })
    expect(cell).toHaveTextContent('aus der Dokumentenklasse')
    expect(screen.getByText('Aufbewahrung')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/retention', {
      params: { path: { id: 'd-1' }, query: { version: 'v-1' } },
    })
  })

  it('shows the rule of the class on a draft that has not taken it over yet (DMS-134)', async () => {
    setLocale('de')
    const { client } = retentionClient({ ...fromClass, rule: undefined, source: 'None' })
    renderRow(client)
    const cell = await screen.findByTestId('retention')
    await waitFor(() => {
      expect(cell).toHaveTextContent('Richtlinien – 10 Jahre ab Ende der Gültigkeit')
    })
    expect(cell).toHaveTextContent('aus der Dokumentenklasse')
    expect(cell).not.toHaveTextContent('keine Aufbewahrungsregel')
  })

  it('tells on a draft whose class has no rule to inform records management', async () => {
    setLocale('de')
    const { client } = retentionClient({
      versionId: 'v-1',
      source: 'None',
      canChange: false,
      rules: [],
    })
    renderRow(client)
    expect(
      await screen.findByText(
        /Für die Dokumentenklasse ist keine Aufbewahrungsregel hinterlegt – bitte das Records Management informieren/,
      ),
    ).toHaveAttribute('role', 'note')
  })

  it('lets records managers set a rule for the document', async () => {
    const { client, put } = retentionClient({
      ...fromClass,
      canChange: true,
      rules: [
        { key: 'policy-10y', name: 'Richtlinien', periodMonths: 120, trigger: 'EndOfValidity' },
        { key: 'short-18m', name: 'Kurz', periodMonths: 18, trigger: 'Approval' },
      ],
    })
    const { invalidate, user } = renderRow(client)
    await user.click(
      await screen.findByRole('button', { name: 'Change the retention rule of this document' }),
    )
    const select = screen.getByRole('combobox', { name: 'Retention rule for this document' })
    expect(select).toHaveValue('')
    expect(
      within(select).getByRole('option', {
        name: 'Rule of the document class (Richtlinien – 10 years from the end of validity)',
      }),
    ).toBeInTheDocument()
    await user.selectOptions(select, 'short-18m')
    await user.click(screen.getByRole('button', { name: 'Save rule' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/retention', {
        params: { path: { id: 'd-1' } },
        body: { rule: 'short-18m' },
      })
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['documents', 'd-1'] })
  })

  it('names a rule set for later versions on an approved version', async () => {
    const { client } = retentionClient({
      ...fromClass,
      documentRule: { key: 'short-18m', name: 'Kurz', periodMonths: 18, trigger: 'Approval' },
    })
    renderRow(client, { ...draft, status: 'Published' })
    expect(
      await screen.findByText(
        'For this document: Kurz – 18 months from approval (open draft and later versions)',
      ),
    ).toBeInTheDocument()
  })
})
