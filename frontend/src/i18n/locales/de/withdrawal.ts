import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/withdrawal'

export const withdrawVersion: Translation<typeof en.withdrawVersion> = {
  action: 'Version {label} zurückziehen',
  title: 'Version {label} zurückziehen?',
  description:
    'Version {label} würde am {validFrom} gültig. Ziehen Sie sie zurück, wird sie nie veröffentlicht, und Version {valid} bleibt gültig. Leser werden nicht benachrichtigt, Autor, Freigebende und Eigentümer schon. Das lässt sich nicht rückgängig machen.',
  descriptionNoValid:
    'Version {label} würde am {validFrom} gültig. Ziehen Sie sie zurück, wird sie nie veröffentlicht, und das Dokument hat keine gültige Version, bis eine neue freigegeben ist. Autor, Freigebende und Eigentümer werden benachrichtigt. Das lässt sich nicht rückgängig machen.',
  descriptionFailed:
    'Die Veröffentlichung von Version {label} ist endgültig fehlgeschlagen. Ziehen Sie sie zurück, wird sie nie veröffentlicht, Version {valid} bleibt gültig, und eine neue Version kann begonnen werden. Leser werden nicht benachrichtigt, Autor, Freigebende und Eigentümer schon. Das lässt sich nicht rückgängig machen.',
  descriptionFailedNoValid:
    'Die Veröffentlichung von Version {label} ist endgültig fehlgeschlagen. Ziehen Sie sie zurück, wird sie nie veröffentlicht, und eine neue Version kann begonnen werden; das Dokument hat keine gültige Version, bis diese freigegeben ist. Autor, Freigebende und Eigentümer werden benachrichtigt. Das lässt sich nicht rückgängig machen.',
  descriptionUnrendered:
    'Das Archiv-PDF von Version {label} konnte nicht erzeugt werden, deshalb kann ihre Freigabe nicht abgeschlossen werden. Ziehen Sie sie zurück, wird sie nie freigegeben oder veröffentlicht, Version {valid} bleibt gültig, und eine neue Version kann begonnen werden. Leser werden nicht benachrichtigt, Autor, Freigebende und Eigentümer schon. Das lässt sich nicht rückgängig machen.',
  descriptionUnrenderedNoValid:
    'Das Archiv-PDF von Version {label} konnte nicht erzeugt werden, deshalb kann ihre Freigabe nicht abgeschlossen werden. Ziehen Sie sie zurück, wird sie nie freigegeben oder veröffentlicht, und aus ihr kann eine neue Version begonnen werden; das Dokument hat keine gültige Version, bis diese freigegeben ist. Autor, Freigebende und Eigentümer werden benachrichtigt. Das lässt sich nicht rückgängig machen.',
  reason: 'Begründung',
  reasonHint:
    'Warum darf diese Version nicht in Kraft treten? Wird in der Versionshistorie angezeigt und im Prüfpfad festgehalten.',
  reasonMissing: 'Bitte geben Sie eine Begründung an.',
  reasonTooLong: 'Höchstens {max} Zeichen ({count} eingegeben).',
  confirm: 'Version zurückziehen',
  confirming: 'Wird zurückgezogen …',
  withdrawn: 'Version {label} zurückgezogen; Version {valid} bleibt gültig.',
  withdrawnNoValid: 'Version {label} zurückgezogen; das Dokument hat keine gültige Version.',
  forbidden: 'Sie dürfen diese Version nicht zurückziehen.',
  conflict: 'Die Version wurde nicht zurückgezogen: {message}',
  failed: 'Die Version wurde nicht zurückgezogen: {message}',
  refused: {
    version_published:
      'Die Version wurde nicht zurückgezogen: Sie wurde zuerst veröffentlicht und ist jetzt gültig. Sie kann nur noch als gültige Version außer Kraft gesetzt werden.',
    version_due:
      'Die Version wurde nicht zurückgezogen: Ihr Gültigkeitsdatum ist erreicht, sie wird gerade veröffentlicht. Sie kann nur noch als gültige Version außer Kraft gesetzt werden.',
    version_withdrawn: 'Diese Version wurde bereits von jemand anderem zurückgezogen.',
    version_not_approved: 'Die Version wurde nicht zurückgezogen: Sie ist nicht freigegeben.',
  },
  actingFor:
    'Sie haben diese Version in Vertretung freigegeben. Das Zurückziehen wird festgehalten i. V. für',
  neverValid: 'Zurückgezogen am {date} von',
  neverValidActingFor: 'i. V. für',
  neverValidReason: 'Begründung: {reason}',
  notice: 'Diese Version wurde vor ihrem Inkrafttreten zurückgezogen und war nie gültig.',
}
