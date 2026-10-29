import type { Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/versioning'

export const newVersion: Translation<typeof en.newVersion> = {
  start: 'Neue Version',
  title: 'Neue Version starten',
  description:
    'Startet die nächste Version als Entwurf aus der gültigen Version {label}. Inhalt, Feldwerte und Anhänge werden übernommen; die gültige Version bleibt gültig, bis die neue veröffentlicht ist.',
  fileDescription:
    'Startet die nächste Version als Entwurf aus der gültigen Version {label}. Die Datei, ihre Feldwerte und die Anhänge werden übernommen; die überarbeitete Datei anschließend im Entwurf hochladen.',
  kind: 'Art der Änderung',
  major: 'Inhaltliche Änderung',
  majorHint: 'Vollständiger Prüf- und Freigabeablauf; Lesende bestätigen die neue Version erneut.',
  minor: 'Redaktionelle Korrektur',
  minorHint:
    'Z. B. Tippfehler, Formulierungen oder defekte Links ohne Änderung der Bedeutung; der Verantwortliche bestätigt sie in einem verkürzten Ablauf.',
  becomes: 'wird Version {label}',
  summary: 'Änderungsübersicht *',
  summaryOptional: 'Änderungsübersicht (empfohlen)',
  summaryHint:
    'Was sich ändert und warum, in verständlichen Worten für Lesende. Erscheint in der Versionshistorie und über Versionsvergleichen; bis zum Einreichen des Entwurfs änderbar.',
  summaryMissing:
    'Bitte beschreiben, was sich ändert und warum: Eine redaktionelle Korrektur braucht eine Änderungsübersicht.',
  summaryTooLong: 'Höchstens {max} Zeichen ({count} eingegeben).',
  submit: 'Version starten',
  submitting: 'Wird gestartet …',
  started: 'Version {label} als Entwurf gestartet.',
  startedFile: 'Version {label} als Entwurf gestartet. Bitte die überarbeitete Datei hochladen.',
  forbidden: 'Sie dürfen keine neue Version dieses Dokuments starten.',
  conflict: 'Die Version wurde nicht gestartet: {message}',
  failed: 'Die Version wurde nicht gestartet: {message}',
  blocked: {
    OpenVersion: 'Eine neue Version ist bereits in Arbeit; bitte zuerst abschließen.',
    ScheduledVersion:
      'Eine freigegebene Version wartet auf ihr Gültigkeitsdatum; die nächste Version erst danach starten.',
  },
  blockedOther: 'Eine neue Version kann gerade nicht gestartet werden.',
}

export const changeSummary: Translation<typeof en.changeSummary> = {
  title: 'Änderungsübersicht',
  kind: 'Art der Änderung: {kind}',
  empty: 'Noch keine Änderungsübersicht.',
  edit: 'Änderungsübersicht bearbeiten',
  label: 'Änderungsübersicht',
  labelRequired: 'Änderungsübersicht *',
  hint: 'Die Übersicht lässt sich bis zum Einreichen des Entwurfs ändern.',
  missing: 'Eine redaktionelle Korrektur braucht eine Änderungsübersicht.',
  save: 'Übersicht speichern',
  saving: 'Wird gespeichert …',
  saved: 'Änderungsübersicht gespeichert.',
  forbidden: 'Sie dürfen die Änderungsübersicht dieses Entwurfs nicht ändern.',
  failed: 'Nicht gespeichert: {message}',
}
