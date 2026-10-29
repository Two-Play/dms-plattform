/**
 * Small typed localization (DMS-110): message catalogs per language, keys checked by the compiler,
 * `{name}` placeholders and plurals through `Intl.PluralRules`. No dependency; see frontend/README.md.
 */

/** Marks plural messages, so a namespace with a key named `other` is never mistaken for one. */
const PLURAL: unique symbol = Symbol('plural')

export type PluralForms = Readonly<Partial<Record<Intl.LDMLPluralRule, string>>> & {
  readonly other: string
}

export type PluralMessage<F extends PluralForms = PluralForms> = F & { readonly [PLURAL]: true }

/** A message with forms per plural category, chosen by the `count` parameter. */
export function plural<const F extends PluralForms>(forms: F): PluralMessage<F> {
  return { ...forms, [PLURAL]: true }
}

function isPlural(value: unknown): value is PluralMessage {
  return typeof value === 'object' && value !== null && PLURAL in value
}

type Leaf = string | PluralMessage

/** The shape another language must provide for a catalog: same keys, any text. */
export type Translation<T> = {
  readonly [K in keyof T]: T[K] extends string
    ? string
    : T[K] extends PluralMessage
      ? PluralMessage
      : Translation<T[K]>
}

/** Dotted keys of all messages of a catalog, e.g. `shell.signOut`. */
export type MessageKeys<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends Leaf ? `${P}${K}` : MessageKeys<T[K], `${P}${K}.`>
}[keyof T & string]

/** The message at a dotted key. */
export type MessageAt<T, K extends string> = K extends `${infer H}.${infer R}`
  ? H extends keyof T
    ? MessageAt<T[H], R>
    : never
  : K extends keyof T
    ? T[K]
    : never

type Placeholders<S> = S extends `${string}{${infer V}}${infer R}` ? V | Placeholders<R> : never

/** Names of the parameters a message needs; plurals always need `count`. */
export type MessageParams<M> =
  M extends PluralMessage<infer F>
    ? 'count' | Placeholders<F[keyof F]>
    : M extends string
      ? Placeholders<M>
      : never

export type ParamValue = string | number

/** `[]` for messages without parameters, `[params]` otherwise. */
export type ParamArgs<M> = [MessageParams<M>] extends [never]
  ? []
  : [params: Record<MessageParams<M>, ParamValue>]

export function lookup(catalog: unknown, key: string): Leaf | undefined {
  let node: unknown = catalog
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || node === null || isPlural(node)) return undefined
    node = (node as Record<string, unknown>)[part]
  }
  return typeof node === 'string' || isPlural(node) ? node : undefined
}

/** Fills a message for `locale` (a BCP 47 tag); numbers are formatted for the locale. */
export function format(
  message: Leaf,
  locale: string,
  params: Readonly<Record<string, ParamValue>> | undefined,
): string {
  let text: string
  if (isPlural(message)) {
    const count = Number(params?.count ?? 0)
    const category = pluralRules(locale).select(count)
    text = (count === 0 ? message.zero : undefined) ?? message[category] ?? message.other
  } else {
    text = message
  }
  if (!params) return text
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name]
    if (value === undefined) return match
    return typeof value === 'number' ? numberFormat(locale).format(value) : value
  })
}

const pluralCache = new Map<string, Intl.PluralRules>()
function pluralRules(locale: string): Intl.PluralRules {
  let rules = pluralCache.get(locale)
  if (!rules) {
    rules = new Intl.PluralRules(locale)
    pluralCache.set(locale, rules)
  }
  return rules
}

const numberCache = new Map<string, Intl.NumberFormat>()
function numberFormat(locale: string): Intl.NumberFormat {
  let f = numberCache.get(locale)
  if (!f) {
    f = new Intl.NumberFormat(locale)
    numberCache.set(locale, f)
  }
  return f
}
