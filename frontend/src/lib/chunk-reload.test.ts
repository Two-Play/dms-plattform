import { afterEach, describe, expect, it, vi } from 'vitest'
import { isChunkLoadError, reloadOnceForChunk } from './chunk-reload'

const failed = new TypeError('Failed to fetch dynamically imported module: /assets/a-1.js')

afterEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://x/assets/a.js',
    'error loading dynamically imported module: https://x/assets/a.js',
    'Importing a module script failed.',
    'Unable to preload CSS for /assets/a.css',
  ])('knows %s', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('leaves other errors alone', () => {
    expect(isChunkLoadError(new Error('boom'))).toBe(false)
    expect(isChunkLoadError('Importing a module script failed.')).toBe(false)
  })
})

describe('reloadOnceForChunk', () => {
  it('reloads once per chunk, so a missing chunk cannot loop', () => {
    const reload = vi.fn()
    expect(reloadOnceForChunk(failed, reload)).toBe(true)
    expect(reloadOnceForChunk(failed, reload)).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('reloads again for the chunk of a later deployment', () => {
    const reload = vi.fn()
    reloadOnceForChunk(failed, reload)
    reloadOnceForChunk(
      new TypeError('Failed to fetch dynamically imported module: /b-2.js'),
      reload,
    )
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('does not reload when nothing could stop the next reload', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const reload = vi.fn()
    expect(reloadOnceForChunk(failed, reload)).toBe(false)
    expect(reload).not.toHaveBeenCalled()
  })
})
