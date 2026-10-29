import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'
import { setLocale } from '@/i18n'
import { clearToasts } from '@/lib/toast'

// `findBy…` and `waitFor` give up after 1 s by default. Under load (a parallel sbt build, several
// test workers creating their jsdom environments) rendering a whole page can take longer, which made
// tests fail at random (DMS-123). A real failure still fails, only later.
configure({ asyncUtilTimeout: 5000 })

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
