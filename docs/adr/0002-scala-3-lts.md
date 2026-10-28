# 0002: Scala 3 LTS (3.9.x) as backend language

| Field | Value |
|---|---|
| Status | Accepted (corrected on the same day from 3.3 LTS to 3.9 LTS, see "Change history") |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The domain model contains many closed sets of states (document status, workflow steps, metadata types) and many business identifiers that must not be mixed up (tenant, document, version). Errors in status transitions or tenant assignment are costly in business and legal terms. Development by one person with AI assistance; longevity of the code matters more than the newest language features.

Scala 3.9.0 has been the new LTS line since September 2026 and replaces 3.3 LTS as the recommended baseline. Artifacts built with 3.9 cannot be read by 3.3 projects. The first build showed that current versions of Tapir (1.14.1) and zio-config (4.1.0) are already published against 3.9.

## Decision

The backend is written in Scala 3 on the LTS line 3.9.x, currently `3.9.0` (centrally in `project/Dependencies.scala`, `Versions.scala`). The Next line (3.10+) is not used.

## Alternatives considered

| Criterion | Chosen: Scala 3.9 LTS | Scala 3.3 LTS | Kotlin + Spring Boot | Java (21/25) | Scala 3 Next (3.10+) |
|---|---|---|---|---|---|
| ADTs / exhaustiveness | `enum`, sealed, full checking | same as chosen | sealed classes, exhaustive `when` | sealed + pattern matching, more verbose | same as chosen |
| Type-safe IDs without runtime cost | `opaque type` | same as chosen | `value class` (restrictions) | Records with allocation | same as chosen |
| Current libraries (Tapir 1.14, zio-config 4.1) | Usable | **Not usable**, pinning to older versions required | n/a | n/a | Usable |
| Support period | New LTS, maintained for several years | At least one more year, ecosystem is moving on | Semi-annual releases | Java LTS | Only until the next minor |
| Minimum JDK | 17 | 8 | 17 | – | 17 |
| Language level | Includes named tuples, improved for-comprehensions, better error messages | State of 2023 | – | – | Newest features |
| Availability of developers | Low | Low | High | Very high | Low |

## Consequences

### Positive

- State machines and IDs are checked by the compiler (`modules/kernel/.../Ids.scala`, `modules/documents/api/.../Lifecycle.scala`).
- Current versions of all chosen libraries are usable; the project starts on the LTS line with the longest remaining maintenance.
- Full access to Java libraries (Flowable, PDF tools, JDBC).

### Negative / Cost

- Libraries published only for 3.3 remain usable (3.9 reads 3.3 artifacts); the reverse does not hold. No drawback for an application, only for library authors.
- 3.9.0 is the first release of the line; early patch releases are likely and should be applied promptly.
- Small hiring market; growing the team or handing over later is harder than with Kotlin or Java.
- Longer compile times than Kotlin or Java, especially with macros and derivation.

## Follow-ups

- Apply patch updates within 3.9.x regularly.
- Library selection: only artifacts published for Scala 3; avoid Scala 2.13 cross-usage.
- Code style defined via `.scalafmt.conf` and sbt-tpolecat.
- Ruled out: Spring ecosystem, Kotlin coroutines, mixed Scala/Kotlin code in the backend.

## Reversibility

**high** for a change of language (rewrite of the backend). **Low** for a change of the Scala 3 line: one version change in `project/Dependencies.scala`, though returning to 3.3 means older library versions.

## Change history

- 2026-10-28: Originally 3.3 LTS (3.3.9). The first build failed ("TASTy signature has wrong version … produced by Scala 3.9.0") because Tapir 1.14.1 and zio-config 4.1.0 are built with 3.9. The options were: pin older library versions (a dead end, the ecosystem is moving to 3.9) or switch to 3.9 LTS. Decided: 3.9 LTS.
