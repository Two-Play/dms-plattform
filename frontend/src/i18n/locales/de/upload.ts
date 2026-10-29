import { type Translation } from '@/i18n/core'
import type * as en from '@/i18n/locales/en/upload'

export const upload: Translation<typeof en.upload> = {
  entry: 'Dokument hochladen',
  title: 'Dokument hochladen',
  description:
    'Laden Sie ein Word-Dokument hoch: Die Plattform füllt die automatischen Felder, Sie füllen die übrigen, prüfen die Vorschau und entscheiden am Ende, ob daraus ein Dokument oder eine Vorlage wird.',
  steps: {
    label: 'Schritte',
    file: 'Datei',
    fields: 'Felder',
    preview: 'Vorschau',
    purpose: 'Verwendung',
    current: 'Schritt {step} von {total}: {label}',
    done: 'erledigt',
  },
  back: 'Zurück',
  next: 'Weiter',
  file: {
    title: 'Datei auswählen',
    text: 'Ein Word-Dokument (DOCX) mit Inhaltssteuerelementen oder {placeholder}-Platzhaltern; auch Excel-, PowerPoint- und PDF-Dateien können Dokumente werden. Vor dem letzten Schritt wird nichts gespeichert.',
    choose: 'Datei',
    analysing: 'Felder werden gelesen …',
    failed: 'Die Datei kann nicht verwendet werden: {message}',
    notConvertible: 'Dateien vom Typ .{extension} können nicht als PDF/A archiviert werden.',
    chosen: '{name} · {size}',
    checksum: 'SHA-256 {sha}',
    filledBefore:
      'Die Plattform hat dieses Dokument schon einmal befüllt: Die enthaltenen Werte wurden übernommen und können geändert werden.',
  },
  fields: {
    title: 'Felder ausfüllen',
    automatic: 'Von der Plattform gefüllt',
    automaticText:
      'Datum, Autor, Nummer, Index, herausgebende Stelle und die übrigen automatischen Felder.',
    manual: 'Ihre Eingaben',
    manualText:
      'Felder der Datei, die Sie ausfüllen. Pflichtfelder müssen vor dem Einreichen gefüllt sein.',
    assignedLater: 'Wird beim Anlegen des Dokuments vergeben',
    none: 'Die Datei hat keine Felder.',
    noManual: 'Die Datei hat keine Felder zum Ausfüllen.',
  },
  preview: {
    title: 'Vorschau prüfen',
    text: 'Die Datei mit den gefüllten Feldern, als Vorschau gekennzeichnet; Nummer und Klassenwerte folgen beim Anlegen des Dokuments.',
    loading: 'Das Dokument wird umgewandelt …',
    failed: 'Die Vorschau ist nicht verfügbar: {message}',
    retry: 'Erneut versuchen',
    region: 'Vorschau von {name}',
  },
  purpose: {
    title: 'Entscheiden, was aus der Datei wird',
    legend: 'Die Datei wird',
    document: 'Ein Dokument',
    documentText:
      'Z. B. eine Richtlinie oder Zentralanweisung: Dokumentklasse wählen, Metadaten eingeben; danach Prüfung, Freigabe und Veröffentlichung wie gewohnt.',
    template: 'Eine Vorlage',
    templateText:
      'Z. B. für Verträge: eine Word-Vorlage mit den Feldern dieser Datei; nach der Freigabe im Vier-Augen-Prinzip unter „Neues Dokument“ wählbar.',
    noDocumentRole: 'Dokumente anlegen erfordert die Rolle Autor oder Verantwortlicher.',
    noTemplateRole: 'Nur Vorlagengestalter können Vorlagen anlegen.',
    notWord: 'Nur Word-Dokumente (DOCX) können Vorlagen werden.',
  },
  document: {
    class: 'Dokumentklasse',
    chooseClass: 'Klasse wählen …',
    loadingClasses: 'Klassen werden geladen …',
    noClasses: 'Es gibt keine Dokumentklasse, in der Sie Dokumente anlegen dürfen.',
    prepare: 'Weiter zu den Metadaten',
    preparing: 'Wird vorbereitet …',
    prepareFailed: 'Die Klasse kann nicht verwendet werden: {message}',
    metadata:
      'Geben Sie Titel und Metadaten der Klasse ein. Beim Speichern wird das Dokument mit Ihrer Datei und den Werten ihrer Felder angelegt.',
    aside:
      'Die Datei und die in den vorigen Schritten eingegebenen Werte werden mit dem Entwurf gespeichert; die Dokumentnummer wird beim Anlegen vergeben.',
    created: 'Dokument mit Ihrer Datei angelegt.',
    fileFailed:
      'Das Dokument wurde angelegt, die Datei aber nicht übernommen: {message}. Laden Sie sie auf der Dokumentseite hoch.',
  },
  template: {
    name: 'Name der Vorlage',
    description: 'Beschreibung',
    class: 'Dokumentklasse',
    chooseClass: 'Klasse wählen …',
    create: 'Vorlage anlegen',
    creating: 'Wird angelegt …',
    created:
      'Vorlage als Entwurf angelegt. Testen Sie sie und lassen Sie sie von einer zweiten Gestalterin oder einem zweiten Gestalter freigeben.',
    failed: 'Die Vorlage konnte nicht angelegt werden: {message}',
  },
  fromVersion: {
    action: 'Als Vorlage verwenden …',
    title: 'Als Vorlage verwenden',
    text: 'Ein neuer Word-Vorlagenentwurf mit dieser Datei und ihren Felddefinitionen, ohne die hier eingegebenen Werte.',
    name: 'Name der Vorlage',
    description: 'Beschreibung',
    create: 'Vorlage anlegen',
    creating: 'Wird angelegt …',
    cancel: 'Abbrechen',
    created: 'Vorlage „{name}“ als Entwurf angelegt.',
    open: 'Vorlage öffnen',
    failed: 'Die Vorlage konnte nicht angelegt werden: {message}',
  },
}
