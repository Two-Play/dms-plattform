import { useState } from 'react'

/** Drag and drop within one list: the handle starts a drag, an item is a drop target. */
export function useDragReorder(onMove: (from: number, to: number) => void) {
  const [dragging, setDragging] = useState<number | undefined>()
  const [over, setOver] = useState<number | undefined>()

  return {
    over: dragging === undefined ? undefined : over,
    handleProps: (index: number) => ({
      draggable: true,
      onDragStart: (e: React.DragEvent) => {
        setDragging(index)
        // Firefox starts a drag only with data.
        e.dataTransfer.effectAllowed = 'move'
        e.dataTransfer.setData('text/plain', String(index))
      },
      onDragEnd: () => {
        setDragging(undefined)
        setOver(undefined)
      },
    }),
    itemProps: (index: number) => ({
      onDragOver: (e: React.DragEvent) => {
        if (dragging === undefined) return
        e.preventDefault()
        e.dataTransfer.dropEffect = 'move'
        setOver(index)
      },
      onDrop: (e: React.DragEvent) => {
        if (dragging === undefined) return
        e.preventDefault()
        onMove(dragging, index)
        setDragging(undefined)
        setOver(undefined)
      },
    }),
  }
}
