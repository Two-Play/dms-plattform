package dms.workflow.core

import dms.workflow.*
import zio.json.*

/** JSON format of workflow definitions as stored in `jsonb` and exchanged over HTTP. */
object WorkflowJson:
  given JsonCodec[StepKind]      = JsonCodec.string.transformOrFail(enumValue(StepKind.valueOf), _.toString)
  given JsonCodec[StepCondition] =
    JsonCodec.string.transformOrFail(enumValue(StepCondition.valueOf), _.toString)
  given JsonCodec[Lane]               = DeriveJsonCodec.gen
  given JsonCodec[Step]               = DeriveJsonCodec.gen
  given JsonCodec[DeadlinePolicy]     = DeriveJsonCodec.gen
  given JsonCodec[WorkflowDefinition] = DeriveJsonCodec.gen

  private def enumValue[A](valueOf: String => A)(s: String): Either[String, A] =
    try Right(valueOf(s))
    catch case _: IllegalArgumentException => Left(s"unknown value $s")
