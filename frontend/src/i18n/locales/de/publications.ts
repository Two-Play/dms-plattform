import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/publications'

export const publications: Translation<typeof en.publications> = {
  retrying:
    'Die Veröffentlichung dieser Version ist fehlgeschlagen (Versuch {attempt} von {max}). Sie wird automatisch erneut versucht, nächster Versuch am {date}.',
  failed:
    'Diese Version konnte nicht veröffentlicht werden: alle {max} Versuche sind fehlgeschlagen. Sie bleibt freigegeben und wird erst gültig, wenn die Veröffentlichung erneut angestoßen wird und gelingt.',
  withdrawHint:
    'Hilft ein neuer Versuch nicht, können Sie diese Version zurückziehen und danach eine neue beginnen: über „Version zurückziehen“ oben.',
  withdrawHintOthers:
    'Hilft ein neuer Versuch nicht, können Inhaber der Eigentümerrolle oder die Freigebenden diese Version zurückziehen; danach lässt sich eine neue Version beginnen.',
  cause: 'Ursache:',
  technicalDetail: 'Technische Details',
  causes: {
    conflict: 'Der Stand des Dokuments lässt die Veröffentlichung nicht zu.',
    legal_hold: 'Auf dem Dokument liegt ein Legal Hold.',
    invalid: 'Die Version kann in ihrem jetzigen Zustand nicht veröffentlicht werden.',
    not_found: 'Die Version wurde nicht gefunden.',
    database: 'Die Datenbank war nicht erreichbar.',
    unknown: 'Die Ursache ist nicht bekannt.',
  },
  retry: 'Jetzt erneut versuchen',
  retryPending: 'Wird erneut versucht …',
  retried: 'Die Veröffentlichung von Version {version} wurde erneut angestoßen.',
  retryForbidden: 'Sie dürfen die Veröffentlichung nicht erneut anstoßen.',
  retryConflict: 'Die Veröffentlichung kann nicht erneut angestoßen werden: {message}',
  retryFailed: 'Die Veröffentlichung konnte nicht erneut angestoßen werden: {message}',
  failures: {
    title: 'Fehlgeschlagene Veröffentlichungen',
    description:
      'Freigegebene Versionen, deren Veröffentlichung fehlgeschlagen ist oder erneut versucht wird. Sie werden erst gültig, wenn sie gelingt. Hilft ein neuer Versuch nicht, können Inhaber der Eigentümerrolle oder die Freigebenden eine Version auf ihrer Dokumentseite zurückziehen.',
    loading: 'Fehlgeschlagene Veröffentlichungen werden geladen',
    empty: 'Keine Veröffentlichung ist fehlgeschlagen.',
    caption: 'Versionen mit fehlgeschlagener Veröffentlichung',
    document: 'Dokument',
    version: 'Version',
    state: 'Stand',
    cause: 'Ursache',
    action: 'Aktion',
    confidential: 'Vertrauliches Dokument',
    failed: 'Fehlgeschlagen',
    retrying: 'Wird erneut versucht',
    nextAttempt: 'nächster Versuch {date}',
  },
} as const
