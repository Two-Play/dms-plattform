import { ArrowDown, ArrowUp, GripVertical } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { useEditorEnv } from './editor-context'
import type { useDragReorder } from './use-drag-reorder'

/**
 * Move controls of a list element: a drag handle that also moves with the arrow keys (Home and End to the
 * ends), and buttons to move up and down. The focus stays on the control that was used.
 */
export function ReorderControls({
  id,
  name,
  index,
  count,
  onMove,
  handleProps,
}: {
  /** Prefix of the controls' DOM ids. */
  id: string
  /** What is moved, for the labels, e.g. "Section Contract". */
  name: string
  index: number
  count: number
  onMove: (to: number, control: string) => void
  handleProps: ReturnType<ReturnType<typeof useDragReorder>['handleProps']>
}) {
  const { readOnly } = useEditorEnv()
  if (readOnly) return null
  return (
    <div className="flex items-center gap-0.5">
      <button
        type="button"
        id={`${id}-handle`}
        aria-label={t('templateEditor.move.handle', { name })}
        aria-describedby="tpl-move-help"
        className="cursor-grab rounded-md p-1 text-muted-foreground hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none active:cursor-grabbing"
        onKeyDown={(e) => {
          const to =
            e.key === 'ArrowUp'
              ? index - 1
              : e.key === 'ArrowDown'
                ? index + 1
                : e.key === 'Home'
                  ? 0
                  : e.key === 'End'
                    ? count - 1
                    : undefined
          if (to === undefined) return
          e.preventDefault()
          if (to >= 0 && to < count && to !== index) onMove(to, `${id}-handle`)
        }}
        {...handleProps}
      >
        <GripVertical className="size-4" aria-hidden="true" />
      </button>
      <Button
        id={`${id}-up`}
        size="icon-xs"
        variant="ghost"
        aria-label={t('templateEditor.move.up', { name })}
        disabled={index === 0}
        onClick={() => {
          onMove(index - 1, index - 1 === 0 ? `${id}-down` : `${id}-up`)
        }}
      >
        <ArrowUp aria-hidden="true" />
      </Button>
      <Button
        id={`${id}-down`}
        size="icon-xs"
        variant="ghost"
        aria-label={t('templateEditor.move.down', { name })}
        disabled={index === count - 1}
        onClick={() => {
          onMove(index + 1, index + 1 === count - 1 ? `${id}-up` : `${id}-down`)
        }}
      >
        <ArrowDown aria-hidden="true" />
      </Button>
    </div>
  )
}
