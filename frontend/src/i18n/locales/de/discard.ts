import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/discard'

export const discardDraft: Translation<typeof en.discardDraft> = {
  action: 'Entwurf verwerfen',
  title: 'Entwurf verwerfen?',
  descriptionFirst:
    'Der Entwurf {label} ist die erste Version dieses Dokuments. Wird er verworfen, verschwindet das Dokument aus Listen, Suche und Aufgabeneingang; der Prüfpfad hält fest, wer ihn wann und warum verworfen hat. Das lässt sich nicht rückgängig machen.',
  descriptionNew:
    'Nur der Entwurf {label} wird verworfen; die gültige Version bleibt unverändert, und Sie können erneut eine neue Version starten. Das lässt sich nicht rückgängig machen.',
  attachmentsNote:
    'Anhänge, die nur dieser Entwurf nutzt, werden gelöscht; Dateien anderer Versionen bleiben unberührt.',
  reason: 'Begründung (optional)',
  reasonHint: 'Z. B. „versehentlich angelegt“. Wird im Prüfpfad festgehalten.',
  reasonTooLong: 'Höchstens {max} Zeichen ({count} eingegeben).',
  confirm: 'Entwurf verwerfen',
  confirming: 'Wird verworfen …',
  discardedDocument: 'Entwurf {label} verworfen; das Dokument wurde entfernt.',
  discardedVersion: 'Entwurf {label} verworfen; Version {valid} bleibt gültig.',
  forbidden: 'Sie dürfen diesen Entwurf nicht verwerfen.',
  conflict: 'Der Entwurf wurde nicht verworfen: {message}',
  failed: 'Der Entwurf wurde nicht verworfen: {message}',
}
