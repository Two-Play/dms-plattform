import { plural, type Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/base'

export const common: Translation<typeof en.common> = {
  product: 'DMS-Plattform',
  loading: 'Wird geladen …',
  retry: 'Erneut versuchen',
  cancel: 'Abbrechen',
  close: 'Schließen',
  save: 'Speichern',
  reload: 'Neu laden',
  document: 'Dokument',
  version: 'Version {label}',
  none: '–',
  yes: 'Ja',
  no: 'Nein',
  breadcrumb: 'Pfadnavigation',
  overdue: 'Überfällig',
  comingSoon: 'Demnächst verfügbar',
}

export const shell: Translation<typeof en.shell> = {
  skipToContent: 'Zum Inhalt springen',
  mainNav: 'Hauptnavigation',
  openMenu: 'Menü öffnen',
  search: 'Dokumente durchsuchen',
  searchPlaceholder: 'Dokumente durchsuchen …',
  openTasks: plural({ one: '{count} offene Aufgabe', other: '{count} offene Aufgaben' }),
  userMenu: 'Benutzermenü',
  signedInUser: 'Angemeldete Person',
  deputies: 'Stellvertretungen',
  signOut: 'Abmelden',
  switchToDark: 'Dunkles Design verwenden',
  switchToLight: 'Helles Design verwenden',
  language: 'Sprache',
  languages: { de: 'Deutsch', en: 'English' },
  nav: {
    home: 'Start',
    documents: 'Dokumente',
    library: 'Bibliothek',
    search: 'Suche',
    tasks: 'Aufgaben',
    templates: 'Vorlagen',
    compliance: 'Compliance',
    admin: 'Administration',
  },
}

export const auth: Translation<typeof en.auth> = {
  intro:
    'Dokumenten- und Vorlagenverwaltung mit Freigabe-Workflows und revisionssicherer Archivierung.',
  signIn: 'Anmelden',
  signInFailed: 'Anmeldung fehlgeschlagen',
  signInFailedWith: 'Anmeldung fehlgeschlagen: {message}',
  noSignIn: 'Keine Anmeldung im Gange',
  signingIn: 'Anmeldung läuft …',
}

export const notFound: Translation<typeof en.notFound> = {
  title: 'Seite nicht gefunden',
  text: 'Die aufgerufene Seite existiert nicht.',
  home: 'Zur Startseite',
}

export const shared: Translation<typeof en.shared> = {
  notifications: 'Benachrichtigungen',
  dismiss: 'Benachrichtigung schließen',
}

export const system: Translation<typeof en.system> = {
  title: 'Systemstatus',
  description: 'Zustandsprüfung des Backends',
  loading: 'Systemstatus wird geladen',
  unavailable: 'Das Backend ist nicht erreichbar',
  status: 'Status',
  version: 'Version',
}

export const errors: Translation<typeof en.errors> = {
  network: {
    title: 'Keine Verbindung zum Server',
    description: 'Bitte die Netzwerkverbindung prüfen und erneut versuchen.',
    inline:
      'Keine Verbindung zum Server. Bitte die Netzwerkverbindung prüfen und erneut versuchen.',
  },
  unauthorized: {
    title: 'Die Sitzung ist abgelaufen',
    description: 'Zum Fortfahren erneut anmelden. Neu laden meldet erneut an.',
    inline: 'Die Sitzung ist abgelaufen. Seite neu laden, um sich erneut anzumelden.',
  },
  forbidden: {
    title: 'Keine Berechtigung',
    description: 'Wenden Sie sich an die Administration, wenn Sie Zugriff benötigen.',
    inline: 'Für diese Aktion fehlt die Berechtigung.',
  },
  notFound: {
    title: 'Nicht gefunden',
    description: 'Möglicherweise wurde es gelöscht oder der Link ist falsch.',
    inline: 'Nicht gefunden. Möglicherweise wurde es inzwischen gelöscht.',
  },
  conflict: {
    title: 'Inzwischen geändert',
    description: 'Neu laden, um den aktuellen Stand zu sehen, und dann erneut versuchen.',
    inline: 'Inzwischen geändert. Neu laden, um den aktuellen Stand zu sehen.',
  },
  invalid: {
    title: 'Die Anfrage konnte nicht verarbeitet werden',
    description: 'Bitte die Eingaben prüfen und erneut versuchen.',
    inline: 'Die Anfrage konnte nicht verarbeitet werden. Bitte die Eingaben prüfen.',
  },
  server: {
    title: 'Auf dem Server ist ein Fehler aufgetreten',
    description:
      'Bitte in einem Moment erneut versuchen. Besteht das Problem weiter, den Support kontaktieren.',
    inline: 'Auf dem Server ist ein Fehler aufgetreten. Bitte gleich erneut versuchen.',
  },
  unknown: {
    title: 'Ein Fehler ist aufgetreten',
    description: 'Bitte erneut versuchen. Besteht das Problem weiter, den Support kontaktieren.',
    inline: 'Ein Fehler ist aufgetreten. Bitte erneut versuchen.',
  },
  partnerUnavailable:
    'Der Partnerstamm ist gerade nicht erreichbar. Der Entwurf lässt sich ohne Partner speichern; der Partner kann später gewählt werden.',
}

export const status: Translation<typeof en.status> = {
  Draft: 'Entwurf',
  InReview: 'In Prüfung',
  InApproval: 'In Freigabe',
  Approved: 'Freigegeben',
  Published: 'Veröffentlicht',
  ReviewDue: 'Rezertifizierung fällig',
  Superseded: 'Abgelöst',
  Withdrawn: 'Außer Kraft',
  Archived: 'Archiviert',
  Destroyed: 'Vernichtet',
  Running: 'Läuft',
  Returned: 'Zurückgegeben',
  Rendering: 'PDF wird erzeugt',
  Open: 'Offen',
  Waiting: 'Wartet',
  Completed: 'Erledigt',
  Cancelled: 'Abgebrochen',
  Testing: 'Im Test',
  Released: 'Freigegeben',
  Deprecated: 'Abgekündigt',
  Scheduled: 'Geplant',
  Active: 'Aktiv',
  Ended: 'Beendet',
  Approve: 'Zustimmung',
  ApproveWithConditions: 'Zustimmung mit Auflagen',
  RequestChanges: 'Änderungen angefordert',
  Reject: 'Ablehnung',
  Review: 'Prüfung',
  Approval: 'Freigabe',
  Major: 'Inhaltlich',
  Minor: 'Redaktionell',
}
