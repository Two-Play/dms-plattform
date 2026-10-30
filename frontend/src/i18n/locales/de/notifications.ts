import { plural } from '@/i18n/core'

/** Benachrichtigungen in der Anwendung, die Glocke und die Einstellungen (DMS-130). */
export const notifications = {
  bell: 'Benachrichtigungen',
  bellUnread: plural({
    one: 'Benachrichtigungen, {count} ungelesen',
    other: 'Benachrichtigungen, {count} ungelesen',
  }),
  menuTitle: 'Benachrichtigungen',
  empty: 'Noch keine Benachrichtigungen.',
  loading: 'Benachrichtigungen werden geladen',
  failed: 'Die Benachrichtigungen konnten nicht geladen werden.',
  markAllRead: 'Alle als gelesen markieren',
  markedAllRead: plural({
    one: '{count} Benachrichtigung als gelesen markiert.',
    other: '{count} Benachrichtigungen als gelesen markiert.',
  }),
  showAll: 'Alle Benachrichtigungen anzeigen',
  settingsLink: 'Benachrichtigungseinstellungen',
  unread: 'Ungelesen',
  received: 'Erhalten {date}',
  categories: {
    workflow: 'Prüfungen und Freigaben',
    deadline: 'Fristen und Eskalationen',
    acknowledgement: 'Kenntnisnahmen',
    deputyship: 'Vertretungen',
    publication: 'Veröffentlichung meiner Dokumente',
    'legal-hold': 'Legal Holds',
  },
  page: {
    documentTitle: 'Benachrichtigungen',
    title: 'Benachrichtigungen',
    description: 'Was bei Ihren Aufgaben, Dokumenten und Vertretungen passiert ist.',
    filter: 'Anzeigen',
    filterAll: 'Alle',
    filterUnread: 'Nur ungelesene',
    list: 'Benachrichtigungen',
    count: plural({ one: '{count} Benachrichtigung', other: '{count} Benachrichtigungen' }),
    paging: 'Seiten',
    previous: 'Zurück',
    next: 'Weiter',
    range: '{from}–{to} von {total}',
    markRead: 'Als gelesen markieren',
    open: 'Öffnen',
    emptyTitle: 'Keine Benachrichtigungen',
    emptyText:
      'Wenn Ihnen eine Aufgabe zugewiesen wird oder sich eines Ihrer Dokumente ändert, finden Sie es hier.',
    emptyUnreadTitle: 'Nichts ungelesen',
    emptyUnreadText: 'Sie haben alle Benachrichtigungen gelesen.',
  },
  settings: {
    documentTitle: 'Benachrichtigungseinstellungen',
    title: 'Benachrichtigungseinstellungen',
    description:
      'Wählen Sie die Sprache der Benachrichtigungen und welche Sie zusätzlich per E-Mail erhalten.',
    language: 'Sprache der Benachrichtigungen',
    languageHint: 'Gilt für E-Mails und für neue Benachrichtigungen in der Anwendung.',
    languageTenant: 'Wie die Organisation ({language})',
    languages: { de: 'Deutsch', en: 'Englisch' },
    email: 'E-Mail',
    emailHint:
      'Benachrichtigungen in der Anwendung erhalten Sie immer. Wählen Sie, welche zusätzlich per E-Mail kommen.',
    emailUnavailable:
      'Der E-Mail-Versand ist auf dieser Plattform nicht eingerichtet; Sie erhalten Benachrichtigungen nur in der Anwendung.',
    emailDefault: 'Standard: {state}',
    on: 'an',
    off: 'aus',
    save: 'Speichern',
    saving: 'Wird gespeichert …',
    saved: 'Benachrichtigungseinstellungen gespeichert.',
    failed: 'Die Einstellungen wurden nicht gespeichert: {message}',
    hints: {
      workflow:
        'Ihnen zugewiesene Aufgaben, zurückgegebene oder freigegebene Versionen, fehlende Teilnehmende.',
      deadline: 'Erinnerungen vor Fristablauf, überfällige Aufgaben und Eskalationen.',
      acknowledgement:
        'Dokumente, die Sie zur Kenntnis nehmen sollen, und Erinnerungen an offene Kenntnisnahmen.',
      deputyship: 'Für Sie oder von Ihnen eingerichtete Vertretungen, ihr Beginn und Ende.',
      publication: 'Ein Dokument, dessen Eigentümer Sie sind, ist gültig geworden.',
      'legal-hold': 'Gesetzte oder aufgehobene Legal Holds (Records Manager).',
    },
  },
} as const
