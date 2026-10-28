# 0020: Template expressions in CEL (Common Expression Language)

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-34) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Templates contain conditions (show a section only for fixed-term contracts), repeat groups (one line per service) and computed fields (total = monthly fee × term) (business concept 4.1, 4.2). The business concept requires a closed, typed expression language: no arbitrary function calls, no access to system resources, bounded loops and runtime (protection against template injection). Expressions are written by template designers, a tenant role, so they are untrusted input for the platform. Errors must be reported when a template is saved, with their position, and expressions are evaluated many times (bulk generation of 20,000 letters, business concept 4.3), so they should be checked and compiled once. The variables are the typed fields of the document class profile (ADR 0019).

## Decision

Conditions, repeat collections and computed fields are CEL expressions, using the Java implementation `dev.cel:cel` 0.15.0. They are type-checked against variables declared from the document class profile (text → string, number → double, amount → map{amount, currency}, date → timestamp, selection → string, yes/no → bool, multiple → list, person/document reference → string) when a template is saved, and compiled once per template version. Evaluation runs with a step budget per expression (evaluation listener), a macro iteration limit, a wall-clock deadline per resolution, a total repeat iteration limit and a maximum output length. Placeholders are plain paths with named formatters, not expressions.

## Alternatives considered

| Criterion | Chosen: CEL (cel-java) | Own mini language (parser + type checker in Scala) | JavaScript sandbox (GraalJS) / Groovy / SpEL / Jinja-like |
|---|---|---|---|
| Closed by design (no I/O, no host access) | Yes: no side effects, no user-defined functions, not Turing complete | Yes, if we build it so | No by default; sandboxing must take away capabilities (class filters, polyglot access), with a history of sandbox escapes |
| Static type checking against the profile | Yes, with error positions (line, column) | Must be built (parser, checker, error recovery) | Mostly dynamic; errors appear at runtime |
| Termination and cost | Not Turing complete; macros bounded by an iteration limit; step budget via evaluation listener | By construction, if built right | Needs timeouts and thread interruption or resource limits (GraalJS: sandbox policies, partly enterprise-only) |
| Expressiveness for conditions and computations | Arithmetic, comparisons, string functions, lists, maps, timestamps, `exists`/`all`/`filter`/`map` | Only what we build | Full programming language, more than needed |
| Familiarity for designers | Medium; C-like syntax; used in Kubernetes, Firebase, Google IAM | Can follow the business concept notation; new to everyone | High for JavaScript, but invites programming in templates |
| Maintenance for one developer | Library maintained by Google; we maintain the type mapping and limits | Grammar, checker, evaluator, error messages, docs: all ours | Engine updates, sandbox configuration, security reviews |
| Dependencies | cel-java with Guava, protobuf-java, ANTLR runtime, re2j (only eviction: snakeyaml 2.2 → 2.7) | None | GraalJS: large runtime; Groovy/SpEL: classpath access to restrict |
| Portability (frontend preview, workers) | Implementations in Go, C++, JavaScript (cel-js) | Must be reimplemented per runtime | JavaScript runs in the browser |

## Consequences

### Positive

- Invalid expressions are reported on save with section, block path, line and column; a released template cannot fail type checks at document creation.
- Untrusted expressions cannot reach the file system, network or the JVM; runaway expressions and huge repeats are stopped by limits and reported as `LimitExceeded`.
- Compiled programs are reused, so bulk generation does not parse expressions per document.
- The test mode can analyze expressions (identifiers, literals) to generate sample data covering every branch.

### Negative / Cost

- Designers learn CEL syntax instead of the notation sketched in the business concept (`{% wenn ... %}`); the editor must offer help (field picker, examples, error positions).
- CEL is strict about numeric types: `termMonths == 12` fails (double vs. int), `12.0` is needed; comparisons (`<`, `>`) work across types because heterogeneous numeric comparisons are enabled.
- Missing optional values must have a defined meaning: they evaluate as empty values, and `present_fields` lists fields that have a value. This is our convention, not CEL's.
- Amounts are maps (`monthlyFee.amount`) and numbers are doubles; exact decimal arithmetic for money in computed fields is not guaranteed (rounding to the currency's digits happens when formatting).
- About 7 MB of additional dependencies (cel-java, Guava, protobuf-java, ANTLR runtime); snakeyaml is raised from 2.2 to 2.7 for the whole build.
- The time limit is checked cooperatively (in the evaluation listener and between blocks), not by interrupting a thread; a single CEL standard function on a very large value could overrun it by its own duration.

## Follow-ups

- Must be done: editor support in the frontend (field picker, inline error positions from the check endpoint); evaluate cel-js for a live preview in the browser.
- Must be done: computed money values with exact decimals if business rules require it (e.g. a custom `decimal` type or computing in minor units).
- Must be done: tune limits with real templates and bulk generation (DMS-37ff.).
- Ruled out: executing JavaScript, Groovy or other general-purpose languages from templates.

## Addendum (DMS-77, 2026-10-28): exact amounts

CEL has no decimal type; expressions keep calculating with doubles. Input amounts are exact `BigDecimal`s. A computed amount (a map with `amount` and `currency`) is converted back via the shortest decimal representation of the double and rounded **half up** (commercial rounding) to the minor unit of its currency (`Money.round`, ISO 4217 digits as used for printing). This makes the typical cases exact (`0.10 + 0.20 = 0.30`, `3 × 33.33 = 99.99`, `19 % of 100.05 = 19.01`, `1.005 → 1.01`). Remaining limit: intermediate results carry about 15–16 significant digits, which is ample for contract amounts below 10^12; rounding to the cent happens once, at the end of each computed field. Alternative rejected for now: a custom decimal type with CEL extension functions (more code, non-standard expressions for template designers).

## Reversibility

**medium**: Expressions are stored as text in the template content; compilation and evaluation are confined to `TemplateCompiler` and `Resolver` in `templates-core`. Replacing CEL means a new compiler/evaluator and migrating stored expressions of released template versions (which must stay renderable), e.g. by keeping CEL for content format 1.
