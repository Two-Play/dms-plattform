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
  userMenu: '{name}, Benutzermenü',
  signedInUser: 'Angemeldete Person',
  deputies: 'Stellvertretungen',
  signOut: 'Abmelden',
  switchToDark: 'Dunkles Design verwenden',
  switchToLight: 'Helles Design verwenden',
  language: 'Sprache',
  languages: { de: 'Deutsch', en: 'English' },
  languageUnsaved: {
    title: 'Sprache wechseln?',
    text: 'Diese Seite hat Änderungen, die nicht gespeichert sind. Der Sprachwechsel lädt sie neu, die Änderungen gehen dann verloren.',
    stay: 'Sprache beibehalten',
    switch: 'Verwerfen und wechseln',
  },
  nav: {
    home: 'Start',
    documents: 'Dokumente',
    library: 'Bibliothek',
    search: 'Suche',
    tasks: 'Aufgaben',
    templates: 'Vorlagen',
    compliance: 'Compliance',
    legalHolds: 'Legal Holds',
    failures: 'Fehlgeschlagene Verarbeitungen',
    retention: 'Aufbewahrung',
    admin: 'Administration',
  },
}

export const auth: Translation<typeof en.auth> = {
  intro:
    'Dokumenten- und Vorlagenverwaltung mit Freigabe-Workflows und revisionssicherer Archivierung.',
  signIn: 'Anmelden',
  signInFailed: 'Anmeldung fehlgeschlagen',
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

export const people: Translation<typeof en.people> = {
  label: 'Person auswählen',
  placeholder: 'Name oder E-Mail …',
  hint: 'Mindestens {count} Zeichen des Namens oder der E-Mail eingeben.',
  results: 'Gefundene Personen',
  searching: 'Suche läuft …',
  found: plural({ one: '{count} Person gefunden.', other: '{count} Personen gefunden.' }),
  shown: '{shown} von {total} Personen angezeigt.',
  refine: '{shown} von {total} Personen angezeigt. Suche verfeinern, um die übrigen zu finden.',
  loadMore: 'Mehr laden',
  loadingMore: 'Weitere Personen werden geladen …',
  loadMoreAgain: 'Erneut versuchen, mehr zu laden',
  alreadyMember: 'Bereits Mitglied',
  alreadyPicked: 'Bereits ausgewählt',
  none: 'Niemand gefunden für „{query}“.',
  clear: 'Auswahl entfernen',
  picked: 'Ausgewählte Personen',
  remove: '{name} entfernen',
  useId: '„{id}“ als Benutzer-ID übernehmen',
  unavailable: 'Personen können gerade nicht gesucht werden.',
  unavailableHint: 'Sie können stattdessen die Benutzer-ID eingeben.',
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
  technicalDetails: 'Technische Details',
  page: {
    title: 'Diese Seite konnte nicht angezeigt werden',
    text: 'Beim Öffnen dieser Seite ist etwas schiefgelaufen. Versuchen Sie es erneut oder laden Sie die Seite neu, wenn das Problem bestehen bleibt.',
    updateTitle: 'Eine neue Version ist verfügbar',
    updateText:
      'Die Anwendung wurde aktualisiert, während Sie gearbeitet haben. Laden Sie die Seite neu, um fortzufahren.',
  },
  codes: {
    acknowledgement_not_requested: 'Für diese Version ist keine Lesebestätigung angefordert.',
    already_acknowledged: 'Sie haben das Lesen dieser Version bereits bestätigt.',
    attachment_blocked: 'Dateien dieses Typs können nicht angehängt werden.',
    attachment_infected: 'Der Virenscanner hat in dieser Datei eine Bedrohung gefunden.',
    attachment_integrity: 'Diese Anlage ist beschädigt und kann nicht gelesen werden.',
    attachment_too_large: 'Diese Datei ist zu groß, um angehängt zu werden.',
    body_too_large: 'Die Anfrage ist zu groß.',
    database_error:
      'Die Anfrage konnte nicht abgeschlossen werden. Bitte versuchen Sie es gleich noch einmal.',
    document_too_long: 'Das Dokument ist zu lang, um daraus ein PDF zu erzeugen. Kürzen oder teilen Sie es.',
    file_not_convertible: 'Dateien dieses Typs können nicht als PDF/A archiviert werden.',
    file_type_mismatch: 'Der Inhalt dieser Datei passt nicht zu ihrem Dateityp.',
    four_eyes: 'Das muss jemand anderes bestätigen: Es gilt das Vier-Augen-Prinzip.',
    incomplete: 'Es fehlen Pflichtangaben.',
    invalid_docx: 'Diese Datei ist kein lesbares Word-Dokument.',
    invalid_office_file: 'Diese Datei ist kein lesbares Office-Dokument.',
    invalid_upload:
      'Dieser Upload kann nicht verwendet werden. Bitte laden Sie die Datei erneut hoch.',
    invalid_values: 'Einige Werte sind ungültig. Bitte die markierten Felder prüfen.',
    lifecycle: 'Dieser Schritt ist im aktuellen Status nicht möglich.',
    no_recipient: 'Für diese Benachrichtigung ist niemand erreichbar.',
    nothing_open: 'Es gibt keine offenen Lesebestätigungen.',
    not_overdue: 'Diese Rezertifizierung ist nicht mehr überfällig.',
    not_opened: 'Öffnen Sie das Dokument, bevor Sie das Lesen bestätigen.',
    notice_too_soon: 'Eine Benachrichtigung dieser Art wurde vor weniger als 24 Stunden gesendet.',
    office_file_too_complex:
      'Diese Datei ist zu groß oder zu komplex für die Verarbeitung. Teilen Sie sie auf oder entfernen Sie nicht benötigte Inhalte und laden Sie sie erneut hoch.',
    partner_directory_invalid_response:
      'Der Partnerstamm ist gerade nicht erreichbar. Der Entwurf lässt sich ohne Partner speichern; der Partner kann später gewählt werden.',
    partner_directory_unavailable:
      'Der Partnerstamm ist gerade nicht erreichbar. Der Entwurf lässt sich ohne Partner speichern; der Partner kann später gewählt werden.',
    pdf_limit_reached:
      'Gerade werden zu viele PDFs erzeugt. Bitte versuchen Sie es gleich noch einmal.',
    publication_not_failed:
      'Die Veröffentlichung ist nicht fehlgeschlagen; es gibt nichts zu wiederholen.',
    purpose_required: 'Bitte geben Sie den Zweck dieses Zugriffs an.',
    reauthentication_required: 'Bitte melden Sie sich erneut an, um diese Aktion zu bestätigen.',
    released: 'Diese Version ist freigegeben und kann nicht mehr geändert werden.',
    reminder_too_soon: 'Es wurde gerade erst erinnert. Bitte versuchen Sie es später noch einmal.',
    report_removed: 'Der Inhalt dieses Berichts wurde nach Ablauf der Aufbewahrung entfernt.',
    rendering_failed: 'Das Dokument konnte nicht erzeugt werden.',
    rendering_time_limit:
      'Das Erzeugen des PDFs hat die Zeitgrenze überschritten. Kürzen oder teilen Sie das Dokument.',
    rendering_unavailable:
      'Das PDF kann gerade nicht erzeugt werden. Bitte versuchen Sie es gleich noch einmal.',
    rendition_not_failed: 'Das Erzeugen ist nicht fehlgeschlagen; es gibt nichts zu wiederholen.',
    rendition_integrity: 'Die archivierte Datei ist beschädigt und kann nicht gelesen werden.',
    report_integrity: 'Der gespeicherte Bericht ist beschädigt und kann nicht gelesen werden.',
    result_changed: 'Das Suchergebnis hat sich geändert. Bitte prüfen Sie es erneut.',
    scanner_unavailable:
      'Der Virenscanner ist gerade nicht erreichbar. Bitte versuchen Sie es gleich noch einmal.',
    scope_required:
      'Dieses Dokument gilt in mehreren Geltungsbereichen. Bitte wählen Sie einen aus.',
    search_error:
      'Die Suche konnte nicht abgeschlossen werden. Bitte versuchen Sie es gleich noch einmal.',
    search_unavailable:
      'Die Suche ist gerade nicht verfügbar. Bitte versuchen Sie es gleich noch einmal.',
    segregation_of_duties:
      'Es gilt das Vier-Augen-Prinzip: Sie können nicht über eine Version entscheiden, an der Sie selbst gearbeitet haben.',
    storage_error:
      'Die Datei konnte nicht gespeichert oder gelesen werden. Bitte versuchen Sie es gleich noch einmal.',
    storage_unavailable:
      'Der Dateispeicher ist gerade nicht erreichbar. Bitte versuchen Sie es gleich noch einmal.',
    too_many_documents:
      'Diese Suche findet zu viele Dokumente. Bitte schränken Sie sie ein und versuchen Sie es erneut.',
    unauthorized_deviations: 'Gesperrte Abschnitte wurden ohne aktiven Abweichungsantrag geändert.',
    unavailable: 'Der Dienst ist ausgelastet. Bitte versuchen Sie es gleich noch einmal.',
    unknown_entry: 'Diesen Partner gibt es im Partnerstamm nicht.',
    unknown_organization: 'Ihre Organisation ist nicht registriert.',
    user_directory_unavailable:
      'Personen können gerade nicht gesucht werden. Bitte versuchen Sie es gleich noch einmal.',
    withdrawn: 'Diese Version wurde zurückgezogen.',
    workflow_error:
      'Die Prüfung konnte nicht fortgesetzt werden. Bitte versuchen Sie es gleich noch einmal.',
  },
  issues: {
    access_groups_widened: 'Nur der Eigentümer des Dokuments kann Zugriffsgruppen hinzufügen.',
    confidentiality_lowered:
      'Eine neue Version darf nicht weniger vertraulich sein als die Version, von der sie ausgeht.',
    missing_required: 'Dieses Feld ist erforderlich.',
    not_editable: 'Dieses Feld setzt die Plattform; es kann nicht eingegeben werden.',
    pattern_mismatch: 'Der Wert hat nicht das erwartete Format.',
    retired_option: 'Diese Option ist nicht mehr verfügbar.',
    unknown_currency: 'Diese Währung ist nicht zugelassen.',
    unknown_field: 'Dieses Feld gehört nicht zur Dokumentklasse.',
    unknown_option: 'Das ist keine Option dieses Felds.',
    wrong_type: 'Der Wert hat den falschen Typ.',
  },
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
  WithdrawnBeforeValid: 'Zurückgezogen, nie gültig',
  Archived: 'Archiviert',
  Destroyed: 'Vernichtet',
  Discarded: 'Verworfen',
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
