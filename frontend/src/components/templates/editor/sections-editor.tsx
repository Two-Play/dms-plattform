import { ChevronDown, ChevronRight, Lock, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { t } from '@/i18n'
import { BlockList, IssueList } from './block-editor'
import {
  duplicateSectionIds,
  move,
  newSection,
  removeAt,
  replaceAt,
  type EditorSection,
} from './content-model'
import { nodeId, useEditorEnv } from './editor-context'
import { ReorderControls } from './reorder'
import { useDragReorder } from './use-drag-reorder'

function sectionName(s: EditorSection, index: number): string {
  const title = s.title?.trim()
  return title
    ? t('templateEditor.section.named', { title })
    : t('templateEditor.section.numbered', { n: String(index + 1) })
}

function SectionCard({
  section,
  index,
  count,
  duplicate,
  handleProps,
  dropTarget,
  onChange,
  onMove,
  onRemove,
}: {
  section: EditorSection
  index: number
  count: number
  duplicate: boolean
  handleProps: ReturnType<ReturnType<typeof useDragReorder>['handleProps']>
  dropTarget: boolean
  onChange: (s: EditorSection) => void
  onMove: (to: number, control: string) => void
  onRemove: () => void
}) {
  const env = useEditorEnv()
  const [open, setOpen] = useState(true)
  const id = nodeId(section.uid)
  const issues = env.issues.get(section.uid)
  const name = sectionName(section, index)
  const idProblem = section.id.trim() === '' ? 'empty' : duplicate ? 'duplicate' : undefined

  return (
    <section
      id={id}
      tabIndex={-1}
      aria-labelledby={`${id}-heading`}
      className={cn(
        'space-y-3 rounded-xl border p-3 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
        section.locked && 'border-amber-500/50 bg-amber-50/40 dark:bg-amber-950/10',
        issues && 'border-destructive/60',
        dropTarget && 'ring-2 ring-primary',
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        <ReorderControls
          id={id}
          name={name}
          index={index}
          count={count}
          onMove={onMove}
          handleProps={handleProps}
        />
        <Button
          size="icon-xs"
          variant="ghost"
          aria-expanded={open}
          aria-controls={`${id}-body`}
          aria-label={
            open
              ? t('templateEditor.section.collapse', { name })
              : t('templateEditor.section.expand', { name })
          }
          onClick={() => {
            setOpen((o) => !o)
          }}
        >
          {open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
        </Button>
        <h4 id={`${id}-heading`} className="flex items-center gap-1.5 text-sm font-semibold">
          {section.locked && (
            <Lock
              className="size-3.5 text-amber-600"
              aria-label={t('templateEditor.section.lockedBadge')}
            />
          )}
          {name}
        </h4>
        {!env.readOnly && (
          <Button
            size="xs"
            variant="ghost"
            className="ml-auto"
            aria-label={t('templateEditor.section.remove', { name })}
            onClick={onRemove}
          >
            <Trash2 aria-hidden="true" />
            {t('templateEditor.section.removeShort')}
          </Button>
        )}
      </div>
      <div id={`${id}-body`} hidden={!open} className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,14rem)]">
          <div className="space-y-1">
            <Label htmlFor={`${id}-title`}>{t('templateEditor.section.title')}</Label>
            <Input
              id={`${id}-title`}
              value={section.title ?? ''}
              readOnly={env.readOnly}
              onChange={(e) => {
                const title = e.target.value
                const next: EditorSection = { ...section }
                if (title === '') delete next.title
                else next.title = title
                onChange(next)
              }}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor={`${id}-sid`}>{t('templateEditor.section.id')}</Label>
            <Input
              id={`${id}-sid`}
              value={section.id}
              readOnly={env.readOnly}
              aria-invalid={
                idProblem || issues?.some((i) => i.location.part === 'id') ? true : undefined
              }
              aria-describedby={`${id}-sid-help`}
              onChange={(e) => {
                onChange({ ...section, id: e.target.value })
              }}
              className="font-mono"
            />
            <p
              id={`${id}-sid-help`}
              className={cn('text-xs', idProblem ? 'text-destructive' : 'text-muted-foreground')}
            >
              {idProblem
                ? t(`templateEditor.section.idProblem.${idProblem}`)
                : t('templateEditor.section.idHint')}
            </p>
          </div>
        </div>
        <div className="flex items-start gap-2">
          <input
            id={`${id}-locked`}
            type="checkbox"
            checked={section.locked}
            disabled={env.readOnly}
            aria-describedby={`${id}-locked-help`}
            onChange={(e) => {
              onChange({ ...section, locked: e.target.checked })
            }}
            className="mt-0.5 size-4"
          />
          <div>
            <Label htmlFor={`${id}-locked`}>{t('templateEditor.section.locked')}</Label>
            <p id={`${id}-locked-help`} className="text-xs text-muted-foreground">
              {t('templateEditor.section.lockedHint')}
            </p>
          </div>
        </div>
        <IssueList id={`${id}-issues`} issues={issues} />
        <BlockList
          blocks={section.blocks}
          label={t('templateEditor.section.blocks', { name })}
          onChange={(blocks) => {
            onChange({ ...section, blocks })
          }}
        />
      </div>
    </section>
  )
}

/** The sections of the template: add, rename, lock, reorder (keyboard and mouse) and remove. */
export function SectionsEditor({
  sections,
  onChange,
}: {
  sections: readonly EditorSection[]
  onChange: (sections: EditorSection[]) => void
}) {
  const env = useEditorEnv()
  const duplicates = duplicateSectionIds(sections)
  const [removing, setRemoving] = useState<number | undefined>()
  const reorder = useDragReorder((from, to) => {
    moveSection(from, to)
  })

  function moveSection(from: number, to: number, control?: string) {
    const s = sections[from]
    if (!s) return
    onChange(move(sections, from, to))
    env.announce(
      t('templateEditor.move.moved', {
        name: sectionName(s, from),
        position: String(to + 1),
        count: String(sections.length),
      }),
    )
    if (control) env.focus(control)
  }

  function remove(index: number) {
    const s = sections[index]
    if (!s) return
    setRemoving(undefined)
    onChange(removeAt(sections, index))
    env.announce(t('templateEditor.section.removed', { name: sectionName(s, index) }))
    const neighbour = sections[index + 1] ?? sections[index - 1]
    env.focus(neighbour ? nodeId(neighbour.uid) : 'tpl-add-section')
  }

  const pending = removing === undefined ? undefined : sections[removing]

  return (
    <div className="space-y-3">
      <p id="tpl-move-help" className="sr-only">
        {t('templateEditor.move.help')}
      </p>
      {sections.length === 0 && (
        <p className="text-sm text-muted-foreground">{t('templateEditor.section.none')}</p>
      )}
      {sections.map((s, i) => (
        <div key={s.uid} {...reorder.itemProps(i)}>
          <SectionCard
            section={s}
            index={i}
            count={sections.length}
            duplicate={duplicates.has(s.id)}
            dropTarget={reorder.over === i}
            handleProps={reorder.handleProps(i)}
            onChange={(next) => {
              onChange(replaceAt(sections, i, next))
            }}
            onMove={(to, control) => {
              moveSection(i, to, control)
            }}
            onRemove={() => {
              const empty = s.blocks.length === 0
              if (empty) remove(i)
              else setRemoving(i)
            }}
          />
        </div>
      ))}
      {!env.readOnly && (
        <Button
          id="tpl-add-section"
          variant="outline"
          onClick={() => {
            const s = newSection(sections, t('templateEditor.section.newTitle'))
            onChange([...sections, s])
            env.focus(`${nodeId(s.uid)}-title`)
          }}
        >
          <Plus aria-hidden="true" />
          {t('templateEditor.section.add')}
        </Button>
      )}
      <Dialog
        open={pending !== undefined}
        onOpenChange={(open) => {
          if (!open) setRemoving(undefined)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t('templateEditor.section.removeTitle', {
                name: pending && removing !== undefined ? sectionName(pending, removing) : '',
              })}
            </DialogTitle>
            <DialogDescription>{t('templateEditor.section.removeText')}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setRemoving(undefined)
              }}
            >
              {t('common.cancel')}
            </Button>
            <Button
              variant="destructive"
              onClick={() => {
                if (removing !== undefined) remove(removing)
              }}
            >
              {t('templateEditor.section.removeConfirm')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
