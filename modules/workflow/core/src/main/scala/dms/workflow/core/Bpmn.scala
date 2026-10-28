package dms.workflow.core

import dms.workflow.*

/** Generates the BPMN 2.0 process of a [[WorkflowDefinition]] (ADR 0024).
  *
  * Shape: start → `resume` gateway (jumps to the step in variable `resumeAt`, see [[Rules.resume]]) → per
  * step an `enter` gateway (skips a conditional step unless variable `extraReview` is true) → parallel fork →
  * one user task per lane → join → next step → end. Lifecycle changes, votes and returns are handled by
  * [[ApprovalWorkflow]], not in the model, so the process holds no business logic beyond routing.
  */
object Bpmn:
  val ResumeAt    = "resumeAt"
  val ExtraReview = "extraReview"

  /** `approval-<class key>`, or `approval.default` for the built-in workflow (class keys contain no dot). */
  def processKey(classKey: Option[String]): String = classKey.fold("approval.default")(k => s"approval-$k")

  /** The process of the editorial path of minor versions ([[WorkflowDefinition.editorial]]). */
  val EditorialKey = "approval.editorial"

  def render(processKey: String, name: String, definition: WorkflowDefinition): String =
    val steps                 = definition.steps.toVector
    def enter(i: Int): String = if i < steps.size then s"enter.${steps(i).key}" else "end"
    val elements              = Vector.newBuilder[String]
    elements += """<startEvent id="start"/>"""
    elements += flow("f.start", "start", "resume")
    elements += """<exclusiveGateway id="resume" default="f.resume._first"/>"""
    elements += flow("f.resume._first", "resume", enter(0))
    steps.zipWithIndex.foreach { (step, i) =>
      elements += flow(s"f.resume.${step.key}", "resume", enter(i), Some(s"$${$ResumeAt == '${step.key}'}"))
    }
    steps.zipWithIndex.foreach { (step, i) =>
      val fork = s"fork.${step.key}"
      val join = s"join.${step.key}"
      step.when match
        case StepCondition.Always =>
          elements += s"""<exclusiveGateway id="${enter(i)}"/>"""
          elements += flow(s"f.${step.key}.enter", enter(i), fork)
        case StepCondition.ExtraReviewRequired =>
          elements += s"""<exclusiveGateway id="${enter(i)}" default="f.${step.key}.skip"/>"""
          elements += flow(s"f.${step.key}.enter", enter(i), fork, Some(s"$${$ExtraReview}"))
          elements += flow(s"f.${step.key}.skip", enter(i), enter(i + 1))
      elements += s"""<parallelGateway id="$fork"/>"""
      step.lanes.foreach { lane =>
        val task = Rules.taskKey(step.key, lane.key)
        elements += s"""<userTask id="$task" name="${escape(s"${step.name}: ${lane.key}")}"/>"""
        elements += flow(s"f.$task.in", fork, task)
        elements += flow(s"f.$task.out", task, join)
      }
      elements += s"""<parallelGateway id="$join"/>"""
      elements += flow(s"f.${step.key}.leave", join, enter(i + 1))
    }
    elements += """<endEvent id="end"/>"""
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
       |             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       |             targetNamespace="urn:dms:workflow">
       |  <process id="$processKey" name="${escape(name)}" isExecutable="true">
       |${elements.result().map("    " + _).mkString("\n")}
       |  </process>
       |</definitions>
       |""".stripMargin

  private def flow(id: String, from: String, to: String, condition: Option[String] = None): String =
    condition match
      case None    => s"""<sequenceFlow id="$id" sourceRef="$from" targetRef="$to"/>"""
      case Some(c) =>
        s"""<sequenceFlow id="$id" sourceRef="$from" targetRef="$to"><conditionExpression xsi:type="tFormalExpression">${escape(
            c
          )}</conditionExpression></sequenceFlow>"""

  private def escape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
