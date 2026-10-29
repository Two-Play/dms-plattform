import type { ReactNode } from 'react'
import { cn } from 'cn'

/** A column of a {@link DataTable}. */
export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  /** Classes for header and cells, e.g. `hidden md:table-cell` for columns hidden on small screens. */
  className?: string
}

/** A simple, consistently styled table with a caption for screen readers. */
export function DataTable<T>({
  rows,
  columns,
  caption,
  rowKey,
  className,
}: {
  rows: readonly T[]
  columns: readonly Column<T>[]
  caption: string
  rowKey: (row: T) => string
  className?: string
}) {
  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                className={cn('py-2 pr-4 font-medium last:pr-0', c.className)}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} className="border-b last:border-0 hover:bg-muted/40">
              {columns.map((c) => (
                <td key={c.key} className={cn('py-2 pr-4 align-top last:pr-0', c.className)}>
                  {c.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
