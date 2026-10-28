import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// jsdom does not implement scrolling; the router's scroll restoration calls it.
window.scrollTo = () => undefined

afterEach(() => {
  cleanup()
})
