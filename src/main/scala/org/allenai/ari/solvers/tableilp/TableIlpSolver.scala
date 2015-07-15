package org.allenai.ari.solvers.tableilp

import org.allenai.ari.models.{ MultipleChoiceSelection, Question }
import org.allenai.ari.solvers.SimpleSolver
import org.allenai.ari.solvers.common.{ EntailmentService, KeywordTokenizer }
import org.allenai.ari.solvers.tableilp.ilpsolver.ScipInterface
import org.allenai.common.Version

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.google.inject.name.Named
import spray.json._

import scala.concurrent.Future

/** An Aristo solver that uses an Integer Linear Programming (ILP) formulation to find the best
  * inference chain of knowledge represented in the form of tables.
  *
  * @param entailmentService service for computing entailment score between two text sequences
  * @param tokenizer keyword tokenizer, also does stemming
  * @param actorSystem the actor system
  */
class TableIlpSolver @Inject() (
    entailmentService: EntailmentService,
    tokenizer: KeywordTokenizer,
    @Named("useEntailment") useEntailment: Boolean
)(implicit actorSystem: ActorSystem) extends SimpleSolver {
  import actorSystem.dispatcher

  override val name = "TableIlp"

  override val version = Version.fromResources("org.allenai.ari", "tableilp-solver")

  /** config: run actual solver or simply generate a random alignement for debugging */
  private val useActualSolver = true

  private val defaultScore = 0d
  private def defaultIlpAnswer(selection: MultipleChoiceSelection) = {
    SimpleAnswer(selection, defaultScore, Some(Map("ilpsolution" -> JsNull)))
  }

  /** The entry point for the solver */
  protected[ari] def handleQuestion(question: Question): Future[Seq[SimpleAnswer]] = {
    // Run the solver asynchronously. This will help prevent your system from timing out or
    // freezing up if you send it lots of questions at once.
    if (!question.isMultipleChoice || question.text.isEmpty) {
      // Return an empty response if this isn't a multiple choice question
      Future.successful(Seq.empty[SimpleAnswer])
    } else {
      Future {
        logger.info(question.toString)

        val ilpSolution = if (useActualSolver) {
          val tables = TableInterface.loadAllTables()
          val scipSolver = new ScipInterface("aristo-tableilp-solver")
          val alignmentType = if (useEntailment) "Entailment" else "Word2Vec"
          val aligner = new AlignmentFunction(alignmentType, Some(entailmentService), tokenizer)
          val ilpModel = new IlpModel(scipSolver, tables, aligner)
          val questionIlp = TableQuestionFactory.makeQuestion(question, "Chunk")
          val allVariables = ilpModel.buildModel(questionIlp)
          scipSolver.solve()
          IlpSolutionFactory.makeIlpSolution(allVariables, scipSolver, questionIlp, tables)
        } else {
          IlpSolutionFactory.makeRandomIlpSolution
        }

        val ilpSolutionJson = ilpSolution.toJson
        logger.debug(ilpSolutionJson.toString())

        val ilpBestAnswer = SimpleAnswer(
          question.selections(ilpSolution.bestChoice),
          ilpSolution.bestChoiceScore,
          Some(Map("ilpsolution" -> ilpSolutionJson))
        )

        val otherAnswers = question.selections.filterNot(_.index == ilpSolution.bestChoice)
          .map(defaultIlpAnswer)

        ilpBestAnswer +: otherAnswers
      }
    }
  }
}
