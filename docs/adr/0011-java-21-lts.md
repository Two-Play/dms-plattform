# 0011: Java 21 LTS as runtime

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Scala 3, ZIO, Flowable, JDBC drivers and veraPDF run on the JVM. Development and runtime environments should be identical and reproducible. It should be possible to operate the platform for several years without a forced JDK change.

## Decision

The runtime is Java 21 LTS, locally Eclipse Temurin `21.0.13` installed via Coursier (`.java-version`: `21`). The Docker image is based on `eclipse-temurin:21-jre` (`build.sbt`).

## Alternatives considered

| Criterion | Chosen: Java 21 LTS | Java 25 LTS | Java 17 LTS |
|---|---|---|---|
| Library support (Scala 3.9, sbt 1.12, Flowable, tooling) | Widely proven | Mostly given, some tools lag behind | Wide |
| Support period (Temurin) | Until at least 2029 | Longer, until approx. 2031 | Ends earlier, approx. 2027 |
| Virtual threads | Yes (final) | Yes, pinning with `synchronized` fixed | No |
| Language and runtime improvements | Baseline | Newer GC and startup time improvements | Older |
| Risk of unknown incompatibilities | Low | Medium | Low |

## Consequences

### Positive

- Greatest compatibility with build tools, IDEs and Java libraries.
- Virtual threads available in case blocking APIs (JDBC, Flowable) benefit from them.
- Long security updates without a platform change during the build-up phase.

### Negative / Cost

- Improvements in Java 25 (including virtual threads without pinning in `synchronized`) are missing; JDBC drivers and Flowable use `synchronized`.
- A switch to Java 25 will foreseeably be needed within the lifetime of the platform.

## Follow-ups

- Keep the JDK version consistent in three places: `.java-version`, Docker base image, CI.
- Pin the Docker base image to a patch version as soon as releases must be reproducible.
- Evaluate the switch to Java 25 as soon as sbt, Scala 3.9 and Flowable officially support it; at the latest before the end of support for 21.
- ZIO uses its own scheduler; use virtual threads only selectively for blocking pools.
- Ruled out: Java features above 21 in dependencies.

## Reversibility

**low**: A JDK upgrade to 25 affects configuration and tests, not application code. A downgrade to 17 would only be necessary if a library did not support 21, which is not expected.
