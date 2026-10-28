# 0004: Build with sbt 1.12

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The build comprises around 20 subprojects (api/core per module, `kernel`, `app`), central version management, formatting, strict compiler options and Docker images. IDE import in IntelliJ and Metals must work reliably, since work slows down considerably without a working IDE.

## Decision

The build uses sbt `1.12.15` (`project/build.properties`) with the plugins sbt-scalafmt `2.6.2`, sbt-native-packager `1.13.0` and sbt-tpolecat `0.5.7`. Moving to sbt 2 remains a later option.

## Alternatives considered

| Criterion | Chosen: sbt 1.12.15 | sbt 2.0 (currently 2.0.11) | Mill |
|---|---|---|---|
| Language of the build definition | Scala 2.12 | Scala 3 | Scala 3 |
| IDE import IntelliJ / Metals | Most proven | Supported, less practical experience | Supported (BSP), less widespread |
| Required plugins (scalafmt, native-packager, tpolecat) | Available | Available | Equivalents exist, partly with a different API |
| Plugin ecosystem overall | Largest | Growing, not all plugins ported | Smaller |
| Remote/build cache | No | Yes, built in | Yes |
| Documentation, answers to problems | Extensive | Limited | Limited |
| Migration effort from here | 0 | Low to medium | Medium |

## Consequences

### Positive

- Predictable IDE import and well-known behaviour.
- All required plugins available in stable versions.
- Broad knowledge base, also for AI-assisted troubleshooting.

### Negative / Cost

- Build definition in the Scala 2.12 dialect, different from the application code in Scala 3.
- No built-in remote cache; longer clean builds in CI.
- A later migration to sbt 2 remains an open task.

## Follow-ups

- Keep the build logic lean (helper functions `module`, `apiModule`, `coreModule` in `build.sbt`), no complex custom plugins.
- Re-evaluate sbt 2 every six months; triggers for switching: stable IDE import, all plugins ported.
- Set up CI caching of the Coursier and sbt directories.
- Ruled out: Gradle/Maven for the backend.

## Reversibility

**low to medium**: The build is small and uses only widespread plugins. Moving to sbt 2 requires adjustments to syntax and plugin versions; moving to Mill requires rewriting the build definition, but no changes to the application code.
