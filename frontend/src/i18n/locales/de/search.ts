import { plural, type Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/search'

export const search: Translation<typeof en.search> = {
  title: 'Suche',
  text: 'Suchbegriff',
  placeholder: 'Titel, Inhalt, Metadaten',
  validOn: 'Gültig am',
  submit: 'Suchen',
  validOnNote:
    'Mit einem „Gültig am“-Datum listet die Seite je Dokument die Version, die an diesem Tag galt (in der Zeitzone der Organisation), und bietet sie als CSV-Export an.',
  validFrom: '· gültig ab {date}',
  startTitle: 'Dokumente mit Leseberechtigung durchsuchen',
  startText:
    'Suchbegriff eingeben oder ein „Gültig am“-Datum wählen, um die an diesem Tag gültigen Versionen zu sehen.',
  searching: 'Suche läuft',
  unavailableTitle: 'Die Suche ist noch nicht verfügbar',
  unavailableText:
    'Dieser Server bietet die Volltextsuche noch nicht an. Dokumente sind unter „Dokumente“ aufgelistet; das „Gültig am“-Datum funktioniert bereits.',
  filters: 'Filter',
  class: 'Klasse',
  status: 'Status',
  language: 'Sprache',
  results: plural({ one: '{count} Treffer', other: '{count} Treffer' }),
  resultList: 'Suchergebnisse',
  noMatch: 'Keine passenden Dokumente.',
  pages: 'Ergebnisseiten',
  previous: 'Zurück',
  next: 'Weiter',
  pageOf: 'Seite {page} von {pages}',
  validOnTitle: 'Gültig am {date}',
  validOnText:
    'Je Dokument und Geltungsbereich die am Ende dieses Tages gültige Version mit ihrer Freigabe.',
  validOnTextZone:
    'Je Dokument und Geltungsbereich die am Ende dieses Tages ({zone}) gültige Version mit ihrer Freigabe.',
  exportCsv: 'CSV exportieren',
  textIgnored:
    'Der Suchbegriff gilt nicht für diese Liste; sie zeigt alle Dokumente mit Leseberechtigung.',
  loadingValid: 'Gültige Versionen werden geladen',
  noneValid: 'An diesem Tag war keine lesbare Dokumentversion gültig.',
  tableCaption: 'Am {date} gültige Versionen',
  colTitle: 'Titel',
  colVersion: 'Version',
  colStatus: 'Status heute',
  colScope: 'Geltungsbereich',
  colValid: 'Gültig',
  colApprovedBy: 'Freigegeben von',
  allScopes: 'Alle',
  today: 'heute',
  onBehalfOf: 'in Vertretung für',
  showing: 'Angezeigt werden die am {date} gültigen Versionen.',
  back: 'Zurück zur Suche',
}
