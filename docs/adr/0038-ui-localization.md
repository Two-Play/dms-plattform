# 0038: UI localization: German default with English, own typed message catalogs, Intl for formats

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-110) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

The users of the platform work in German (business concept); the UI was English. DMS-110 asks for language files with German as default and English, a language switch in the user menu (remembered per user, default from the browser), dates and numbers formatted for the language, `<html lang>` kept up to date, and every UI text translated (status labels, errors, toasts, empty states, dialogs, aria labels, page titles). Keys should be checked by the compiler.

Given:

- About 900 texts in ~60 components; the shell, the dashboard and the shared building blocks already kept their texts in one object per component (DMS-109).
- Two languages, both with the plural rules one/other; plurals occur in counters ("3 open tasks").
- Some texts are produced outside components: toasts after mutations, error texts (`error-messages.ts`), validation messages of the generated form, labels of statuses and decisions.
- Backend messages (`ApiError.message`) are English and stay so; the UI shows its own text first and the backend's detail below it.

## Decision

1. **Own small i18n module** (`src/i18n/`, about 200 lines): message catalogs per language as TypeScript objects (`locales/en/*.ts`, `locales/de/*.ts`), one namespace per area. English is the source catalog; German must have the same shape (`Translation<typeof en…>`), so a missing or extra key fails the build. `t('namespace.key', params)` checks the key and infers the required parameters from the `{placeholders}` of the English text. Plurals are marked with `plural({ one, other, zero? })` and chosen with `Intl.PluralRules`; numbers in parameters are formatted with `Intl.NumberFormat`. A unit test checks that every German message has the same placeholders as its English one.
2. **Language state**: a module-level store (`getLocale`, `setLocale`, `useLocale`). Default: the stored choice, else the browser's preferred language if it is German or English, else German. The choice is kept in `localStorage` (`dms-locale`), and per signed-in user (`dms-locale:<sub>`), so people sharing a browser keep their own language. `setLocale` sets `<html lang>`. The switch is a radio group in the user menu.
3. **Re-rendering**: `App` keys the router by the language, so switching renders all pages again with the new texts and formats; data stays in the query cache and the location in the URL. Therefore `t` can be called anywhere (components, helpers, toasts) without a hook; only texts built at module load time are not allowed (column definitions and label tables are functions).
4. **Formats**: `src/i18n/format.ts` (`formatDate`, `formatDateTime`, `formatPlainDate`, `formatNumber`, `formatPercent`) use `Intl` with `de-DE` or `en-GB`; no `Intl.DateTimeFormat(undefined, …)` remains.
5. **Tone**: German texts use neutral, impersonal forms ("Entwurf speichern", "Bitte einen Titel eingeben") and "Sie" where a direct address cannot be avoided; terms follow the business concept (Dokument, Vorlage, Entwurf, In Prüfung, In Freigabe, Freigegeben, Veröffentlicht, Kenntnisnahme, Rezertifizierung, Stellvertretung, Außer Kraft setzen, Abweichung, Vier-Augen-Prinzip, Prüfer/in, Genehmiger/in, Verantwortliche/r).
6. **Tests** run in English (`src/test/setup.ts` sets it before each test), so existing tests keep their intent; German smoke tests switch with `setLocale('de')`.

## Alternatives considered

| Criterion | Chosen: own typed catalogs | react-i18next + i18next | FormatJS (react-intl) |
|---|---|---|---|
| Typed keys and parameters | Built in (template literal types over the catalog) | Possible with module augmentation of `CustomTypeOptions`; parameters typed only partly | Keys are free strings; extraction tooling instead |
| Plurals | `Intl.PluralRules`, enough for de/en | Built in (suffix keys `_one`, `_other`) | Full ICU MessageFormat |
| Bundle size | ~2 kB | ~40 kB (i18next + react-i18next) | ~30 kB |
| Usable outside components | Yes (`t` is a plain function) | Yes (`i18next.t`) | Needs the intl object |
| Features we lack | Namespace lazy loading, ICU select, translation management tools | – | – |
| Dependencies to keep current | None | Two | Several |

## Consequences

### Positive

- The compiler catches missing translations, unknown keys and missing parameters; no runtime "missing key" surprises.
- No dependency; behaviour is small enough to read in one file.
- Texts of the whole UI are in a few files per language, ready for review by the business side.

### Negative / Cost

- No ICU select/gender syntax and no tooling for translators (they edit TypeScript files).
- Switching the language re-renders the pages from scratch; unsaved input in an open form is lost.
- All catalogs are in the main bundle (fine for two languages).
- Backend messages shown as details stay English.

## Follow-ups

- Later: translate backend error messages (or send codes only) when the API is opened to German-speaking integrators.
- Later: move to i18next if a third language, lazy loading or a translation management system is needed; the catalog structure maps to i18next namespaces one to one.
- Ruled out: translating the content of documents and templates (that is the document language, ADR 0007).

## Reversibility

**high**: The catalogs are plain objects and `t` has the same shape as i18next's; replacing the module touches imports, not texts.
