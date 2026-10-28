package dms.integration.partners

import zio.*

import java.time.Instant

/** A small circuit breaker for calls to a business system (ADR 0023).
  *
  * Closed: calls pass; after `failureThreshold` failures in a row it opens. Open: calls fail at once with the
  * `rejected` error for `openFor`, so authors get the "not reachable" message immediately instead of waiting
  * for a timeout on every keystroke. Then one trial call passes (half-open): success closes the breaker,
  * failure opens it again. Other calls during the trial are rejected.
  */
final class CircuitBreaker private (
    state: Ref[CircuitBreaker.State],
    failureThreshold: Int,
    openFor: Duration
):
  import CircuitBreaker.State

  def apply[E, A](effect: IO[E, A])(isFailure: E => Boolean, rejected: => E): IO[E, A] =
    for
      now     <- Clock.instant
      allowed <- state.modify {
        case State.Open(until) if !now.isBefore(until) => (true, State.HalfOpen)
        case s @ (State.Open(_) | State.HalfOpen)      => (false, s)
        case s: State.Closed                           => (true, s)
      }
      _      <- ZIO.unless(allowed)(ZIO.fail(rejected))
      result <- effect.tapBoth(
        e => ZIO.when(isFailure(e))(failed).unit,
        _ => state.set(State.Closed(0))
      )
    yield result

  /** Whether calls are currently rejected, for health information. */
  def isOpen: UIO[Boolean] = state.get.map {
    case State.Closed(_) => false
    case _               => true
  }

  private def failed: UIO[Unit] =
    Clock.instant.flatMap { now =>
      val open = State.Open(now.plusMillis(openFor.toMillis))
      state.update {
        case State.Closed(n) if n + 1 < failureThreshold => State.Closed(n + 1)
        case _                                           => open
      }
    }

object CircuitBreaker:
  private enum State:
    case Closed(failures: Int)
    case Open(until: Instant)
    case HalfOpen

  def make(failureThreshold: Int, openFor: Duration): UIO[CircuitBreaker] =
    Ref.make[State](State.Closed(0)).map(CircuitBreaker(_, failureThreshold.max(1), openFor))
