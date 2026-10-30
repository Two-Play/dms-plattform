import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/renditions'

export const renditions: Translation<typeof en.renditions> = {
  loading: 'Stand des Archiv-PDFs wird geladen',
  pending: 'Das Archiv-PDF wird erzeugt; danach ist die Version freigegeben.',
  retrying:
    'Das Archiv-PDF konnte nicht erzeugt werden (Versuch {attempt} von {max}). Es wird automatisch erneut versucht, nächster Versuch am {date}.',
  failed:
    'Das Archiv-PDF konnte nicht erzeugt werden: alle {max} Versuche sind fehlgeschlagen. Die Version bleibt in Freigabe, bis das Erzeugen erneut angestoßen wird.',
  done: 'Das Archiv-PDF ist erzeugt.',
  none: 'Für diese Version wurde kein Archiv-PDF angefordert.',
  cause: 'Ursache:',
  technicalDetail: 'Technische Details',
  causes: {
    unavailable:
      'Der Rendering- oder Konvertierungsdienst oder der Objektspeicher war nicht erreichbar. Das ist meist vorübergehend.',
    rejected:
      'Der Rendering-Dienst hat den Inhalt abgelehnt, z. B. weil er auf eine externe Ressource verweist.',
    not_archivable:
      'Das PDF besteht die Prüfung gegen den Archivstandard (PDF/A-2b) nicht, z. B. wegen einer nicht eingebetteten Schrift.',
    invalid: 'Die Version kann in ihrem jetzigen Zustand nicht gerendert werden.',
    integrity: 'Eine gespeicherte Datei stimmt nicht mit ihrer Prüfsumme überein.',
    database: 'Die Datenbank war nicht erreichbar.',
    unknown: 'Die Ursache ist nicht bekannt.',
  },
  retry: 'Jetzt erneut versuchen',
  retryPending: 'Wird erneut versucht …',
  retried: 'Das Erzeugen des Archiv-PDFs von Version {version} wurde erneut angestoßen.',
  retryForbidden: 'Sie dürfen das Erzeugen nicht erneut anstoßen.',
  retryConflict: 'Das Erzeugen kann nicht erneut angestoßen werden: {message}',
  retryFailed: 'Das Erzeugen wurde nicht erneut angestoßen: {message}',
  failures: {
    title: 'Fehlgeschlagene Archiv-PDFs',
    description:
      'Versionen, deren Archiv-PDF fehlgeschlagen ist oder gerade erneut versucht wird. Sie bleiben in Freigabe, bis es erzeugt ist.',
    loading: 'Fehlgeschlagene Archiv-PDFs werden geladen',
    empty: 'Kein Archiv-PDF ist fehlgeschlagen.',
    caption: 'Versionen mit fehlgeschlagenem Archiv-PDF',
    document: 'Dokument',
    version: 'Version',
    state: 'Stand',
    attempts: 'Versuche',
    cause: 'Ursache',
    action: 'Aktion',
    confidential: 'Vertrauliches Dokument',
    states: {
      Failed: 'Fehlgeschlagen',
      Retrying: 'Wird erneut versucht',
      Pending: 'Ausstehend',
      Done: 'Erzeugt',
      None: 'Nicht angefordert',
    },
    nextAttempt: 'nächster Versuch {date}',
  },
}
