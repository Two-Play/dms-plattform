package dms.templates.core

import dev.cel.common.types.{CelKind, CelType}
import dms.templates.*

/** A computed field that has no value when optional fields are empty, which blocks the approval of a document
  * (DMS-83).
  *
  * @param missingInputs
  *   the optional fields it needs, in schema order
  * @param guard
  *   the check to put in front of the calculation, e.g. `'termMonths' in present_fields`
  * @param suggestion
  *   the whole calculation with the guard and an empty fallback value; `None` where the result type has no
  *   empty value (dates, amounts) or the template would not compile with it
  * @param cases
  *   the test cases that showed this
  */
final case class ComputedHint(
    key: String,
    label: String,
    missingInputs: List[MissingValue],
    guard: String,
    suggestion: Option[String],
    cases: List[String]
)

/** A condition that read a missing value as empty, with the test cases that showed this. */
final case class TestConditionHint(hint: ConditionHint, cases: List[String])

/** What the test cases found about missing values (ADR 0065). */
final case class TestHints(computed: List[ComputedHint], conditions: List[TestConditionHint])

object TestHints:

  def from(
      template: PreviewTemplate,
      cases: List[(TestCase, Either[ResolveError, Resolution])]
  ): TestHints =
    TestHints(computedHints(template, cases), conditionHints(template, cases))

  /** Only optional fields count: a required field has to be filled in anyway, a guard would hide that. A case
    * that failed in a condition still shows its computed fields, which were evaluated before.
    */
  private def computedHints(
      template: PreviewTemplate,
      cases: List[(TestCase, Either[ResolveError, Resolution])]
  ): List[ComputedHint] =
    val compiled = template.compiled
    val optional = compiled.schema.variables.filterNot(_.required).map(_.name)
    val found    = cases.flatMap { (c, result) =>
      val missing = result.map(_.missingComputed).orElse(Resolver.missingComputed(compiled, c.values))
      missing.toOption.toList.flatten.map(c.name -> _)
    }
    compiled.computed.flatMap { (field, expression) =>
      val own    = found.filter((_, m) => m.key == field.key && m.missingInputs.exists(optional.contains))
      val inputs = optional.filter(key => own.exists((_, m) => m.missingInputs.contains(key)))
      Option.when(inputs.nonEmpty) {
        val check = guard(inputs)
        ComputedHint(
          field.key,
          field.displayLabel,
          template.named(inputs),
          check,
          suggestion(compiled, field, expression, check),
          own.map(_._1).distinct
        )
      }
    }

  private def conditionHints(
      template: PreviewTemplate,
      cases: List[(TestCase, Either[ResolveError, Resolution])]
  ): List[TestConditionHint] =
    val found =
      cases.flatMap((c, result) => result.toOption.toList.flatMap(_.conditionsOnMissing).map(c.name -> _))
    val order = template.compiled.schema.variables.map(_.name)
    template.compiled.conditionals.flatMap { info =>
      val location = info.expression.location
      val own      = found.filter((_, c) => c.location == location)
      val inputs   = order.filter(key => own.exists((_, c) => c.missingInputs.contains(key)))
      Option.when(own.nonEmpty)(
        TestConditionHint(
          ConditionHint(location, info.expression.source, template.named(inputs)),
          own.map(_._1).distinct
        )
      )
    }

  private def guard(inputs: List[String]): String =
    inputs.map(key => s"'$key' in ${Cel.PresentFields}").mkString(" && ")

  /** The calculation behind the guard. It is only offered if the template still compiles with it, so a
    * designer never gets a suggestion the save would refuse (types, length limit).
    */
  private def suggestion(
      compiled: CompiledTemplate,
      field: ComputedField,
      expression: CompiledExpression,
      guard: String
  ): Option[String] =
    emptyLiteral(expression.resultType)
      .map { fallback =>
        // CEL's grammar needs parentheses around a conditional in the first branch.
        val body = if Cel.isConditional(expression.ast) then s"(${field.expression})" else field.expression
        s"$guard ? $body : $fallback"
      }
      .filter { guarded =>
        val computed =
          compiled.content.computed.map(c => if c.key == field.key then c.copy(expression = guarded) else c)
        TemplateCompiler
          .compile(compiled.content.copy(computed = computed), compiled.schema, compiled.limits)
          .isRight
      }

  /** The literal for "nothing" of a result type. Dates and amounts have none: any date or currency would be
    * an invented value on a document, so the designer chooses it.
    */
  private def emptyLiteral(tpe: CelType): Option[String] = tpe.kind match
    case CelKind.DOUBLE => Some("0.0")
    case CelKind.INT    => Some("0")
    case CelKind.UINT   => Some("0u")
    case CelKind.STRING => Some("''")
    case CelKind.BOOL   => Some("false")
    case CelKind.LIST   => Some("[]")
    case _              => None
