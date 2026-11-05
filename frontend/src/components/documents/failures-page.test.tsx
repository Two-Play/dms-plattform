import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { hasRestrictedRecordsRole } from '@/api/legal-holds'
import type { Me, RoleAssignment } from '@/api/me'
import { errorCode } from '@/api/renditions'
import { publicationErrorCode } from '@/api/publications'
import { setLocale } from '@/i18n'
import { FailuresPage } from './failures-page'

/** The page of the failures a person's role covers or they are named for (DMS-146). */

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: ReactNode }) => <a href="/documents">{children}</a>,
}))

const failure = {
  documentId: '7c1e2f0a-0000-4000-8000-0000000000d1',
  versionId: '7c1e2f0a-0000-4000-8000-0000000000e1',
  version: '2.0',
  title: 'Reiserichtlinie',
  state: 'Failed',
  attempts: 10,
  maxAttempts: 10,
  requestedAt: '2026-10-29T10:00:00Z',
  failedAt: '2026-10-29T11:00:00Z',
  lastError: { code: 'timeout', message: 'timed out after 270 s' },
  canRetry: true,
}

function me(...assignments: Partial<RoleAssignment>[]): Me {
  return { assignments } as Me
}

function renderPage(renditions: unknown[], publications: unknown[]) {
  const get = vi.fn((path: string) =>
    Promise.resolve({
      data: path.includes('renditions') ? renditions : publications,
      error: undefined,
      response: new Response(null, { status: 200 }),
    }),
  )
  const client = { GET: get } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <FailuresPage />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  setLocale('en')
})

describe('FailuresPage', () => {
  it('lists failed archive PDFs and publications with their cause and a retry', async () => {
    setLocale('en')
    renderPage([failure], [{ ...failure, lastError: { code: 'internal', message: 'boom' } }])
    expect(await screen.findByText('The rendering did not finish in time.')).toBeTruthy()
    expect(await screen.findByText('An internal error occurred in the platform.')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: 'Retry now' })).toHaveLength(2)
  })

  it('stays empty for someone with nothing to act on', async () => {
    setLocale('en')
    renderPage([], [])
    expect(await screen.findByText('No archive PDF has failed.')).toBeTruthy()
    expect(await screen.findByText('No publication has failed.')).toBeTruthy()
  })
})

describe('hasRestrictedRecordsRole', () => {
  it('finds records managers of a unit or class only', () => {
    expect(
      hasRestrictedRecordsRole(me({ role: 'RecordsManager', organizationalUnit: 'Legal' })),
    ).toBe(true)
    expect(
      hasRestrictedRecordsRole(me({ role: 'RecordsManager', documentClass: 'contract' })),
    ).toBe(true)
    expect(
      hasRestrictedRecordsRole(
        me({ role: 'RecordsManager', organizationalUnit: 'Legal' }, { role: 'RecordsManager' }),
      ),
    ).toBe(false)
    expect(hasRestrictedRecordsRole(me({ role: 'Reader' }))).toBe(false)
    expect(hasRestrictedRecordsRole(undefined)).toBe(false)
  })
})

describe('failure codes', () => {
  it('knows timeout and internal for both jobs', () => {
    for (const code of ['timeout', 'internal']) {
      expect(errorCode({ code, message: '' })).toBe(code)
      expect(publicationErrorCode({ code, message: '' })).toBe(code)
    }
  })
})
