import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'
import { setLocale } from '@/i18n'
import { clearToasts } from '@/lib/toast'

// jsdom does not implement scrolling; the router's scroll restoration calls it.
window.scrollTo = () => undefined

// Tests run in English (the source language of the message catalogs) unless a test switches the
// language itself, e.g. with `renderInLocale` or `setLocale('de')` for German smoke tests.
beforeEach(() => {
  setLocale('en')
})

afterEach(() => {
  cleanup()
  clearToasts()
})
