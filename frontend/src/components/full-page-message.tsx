import type { ReactNode } from 'react'

export function FullPageMessage({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="font-heading text-xl font-semibold">{title}</h1>
      {children}
    </main>
  )
}
