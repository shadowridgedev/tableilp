package org.allenai.ari.solvers.tableilp

import org.allenai.ari.solvers.common.{ EntailmentService, KeywordTokenizer }
import org.allenai.common.Logging

import com.medallia.word2vec.Word2VecModel

import java.io.File

// Various options for computing similarity
sealed trait SimilarityType {
  def scoreTitleTitle(titleStr1: String, titleStr2: String): Double // should be symmetric
  def scoreCellCell(cellStr1: String, cellStr2: String): Double // should be symmetric
  def scoreCellQCons(cellStr: String, qConsStr: String): Double // directional: qCons to cell
  def scoreTitleQCons(titleStr: String, qConsStr: String): Double // directional: title to qCons

  // turn a one-sided score into a symmetric one
  protected def getSymmetricScore(text1: String, text2: String,
    scoringFunction: (String, String) => Double): Double = {
    (scoringFunction(text1, text2) + scoringFunction(text2, text1)) / 2d
  }
}

class AlignmentFunction(
    alignmentType: String,
    entailmentServiceOpt: Option[EntailmentService],
    tokenizer: KeywordTokenizer
) extends Logging {
  private val similarityFunction: SimilarityType = alignmentType match {
    case "Entailment" => {
      logger.info("Using entailment for alignment score computation")
      val teService = entailmentServiceOpt match {
        case Some(entailmentService) => entailmentService
        case None => throw new IllegalStateException("No entailment service available")
      }
      new EntailmentSimilarity(teService, tokenizer)
    }
    case "Word2Vec" => {
      logger.info("Using word2vec for alignment score computation")
      new Word2VecSimilarity
    }
    case "WordOverlap" => {
      logger.info("Using word overlap for alignment score computation")
      new WordOverlapSimilarity(tokenizer)
    }
    case _: String => {
      throw new IllegalArgumentException(s"Alignment type $alignmentType not recognized")
    }
  }

  /** Alignment score between two titles of tables */
  def scoreTitleTitle(titleStr1: String, titleStr2: String): Double = {
    similarityFunction.scoreTitleTitle(titleStr1, titleStr2)
  }

  /** Alignment score between cells of two tables */
  def scoreCellCell(cellStr1: String, cellStr2: String): Double = {
    similarityFunction.scoreCellCell(cellStr1, cellStr2)
  }

  /** Alignment score between a cell of a table, and a question constituent */
  def scoreCellQCons(cellStr: String, qConsStr: String): Double = {
    similarityFunction.scoreCellQCons(cellStr, qConsStr)
  }

  /** Alignment score between a title of a table, and a question constituent */
  def scoreTitleQCons(titleStr: String, qConsStr: String): Double = {
    similarityFunction.scoreTitleQCons(titleStr, qConsStr)
  }

}

// how much does text1 entail text2? (directional)
private class EntailmentSimilarity(
    entailmentService: EntailmentService,
    tokenizer: KeywordTokenizer
) extends SimilarityType {
  def scoreTitleTitle(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getEntailmentScore)
  }
  def scoreCellCell(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getEntailmentScore)
  }
  def scoreCellQCons(text1: String, text2: String): Double = getEntailmentScore(text2, text1)
  def scoreTitleQCons(text1: String, text2: String): Double = getEntailmentScore(text2, text1)

  // an entailment score below this value is considered to have negative correlation
  private val entailmentScoreOffset = 0.2
  private val sep = ";".r
  private def getEntailmentScore(text1: String, text2: String): Double = {
    val text1StemmedTokens = sep.split(text1).map(s => tokenizer.stemmedKeywordTokenize(s.trim))
    val text2StemmedTokens = sep.split(text2).map(s => tokenizer.stemmedKeywordTokenize(s.trim))
    val scores = for {
      text1StemmedTokens <- text1StemmedTokens
      text2StemmedTokens <- text2StemmedTokens
    } yield {
      entailmentService.entail(text1StemmedTokens, text2StemmedTokens).confidence -
        entailmentScoreOffset
    }
    scores.max
  }
}

// cosine distance between two pieces of text (inherently symmetric)
private class Word2VecSimilarity extends SimilarityType {
  def scoreTitleTitle(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreCellCell(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreCellQCons(text1: String, text2: String): Double = getWord2VecScore(text2, text1)
  def scoreTitleQCons(text1: String, text2: String): Double = getWord2VecScore(text2, text1)

  private val word2vecFile = new File(
    "main/resources/vectors/GoogleNews-vectors-negative300_size=200000.bin"
  )
  private val w2vModel = Word2VecModel.fromBinFile(word2vecFile)
  private val w2vNoMatchStr = "</s>" // string used by word2vec when there is no match
  private def getWord2VecScore(text1: String, text2: String): Double = {
    val text1Modified = if (w2vModel.forSearch().contains(text1)) text1 else w2vNoMatchStr
    val text2Modified = if (w2vModel.forSearch().contains(text2)) text2 else w2vNoMatchStr
    w2vModel.forSearch().cosineDistance(text1Modified, text2Modified)
  }
}
// what fraction of text2 words are "covered" by text1 words? (directional)
private class WordOverlapSimilarity(tokenizer: KeywordTokenizer) extends SimilarityType {
  def scoreTitleTitle(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getWordOverlap)
  }
  def scoreCellCell(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getWordOverlap)
  }
  def scoreCellQCons(text1: String, text2: String): Double = getWordOverlap(text1, text2)
  def scoreTitleQCons(text1: String, text2: String): Double = getWordOverlap(text1, text2)

  private def getWordOverlap(text1: String, text2: String): Double = {
    val text1StemmedTokens = tokenizer.stemmedKeywordTokenize(text1)
    val text2StemmedTokens = tokenizer.stemmedKeywordTokenize(text2)
    val coverage = text2StemmedTokens.intersect(text1StemmedTokens).size
    coverage.toDouble / text2StemmedTokens.size
  }
}
