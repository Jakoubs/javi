package chess.util

import chess.model.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Try

/**
 * Manages loading of challenges and player progress persistence.
 */
object OdysseyManager:
  private val PROGRESS_FILE = "odyssey_progress.json"
  private val CHALLENGES_FILE = "challenges.json"

  // ── Persistence ────────────────────────────────────────────────────────────

  case class ProgressData(completedIds: Set[Int])

  def saveProgress(completedIds: Set[Int]): Unit =
    val data = ProgressData(completedIds)
    val json = Encoder[ProgressData].apply(data).noSpaces
    val writer = new PrintWriter(new File(PROGRESS_FILE))
    try {
      writer.write(json)
    } finally {
      writer.close()
    }

  def loadProgress(): Set[Int] =
    if !new File(PROGRESS_FILE).exists() then Set.empty
    else
      val source = Source.fromFile(PROGRESS_FILE)
      val json = try source.mkString finally source.close()
      decode[ProgressData](json).map(_.completedIds).getOrElse(Set.empty)

  // ── Challenge Loading (Factory Pattern) ────────────────────────────────────

  def loadChallenges(): List[Challenge] =
    if !new File(CHALLENGES_FILE).exists() then 
      println(s"Warning: $CHALLENGES_FILE not found.")
      return Nil
    
    val source = Source.fromFile(CHALLENGES_FILE)
    val jsonString = try source.mkString finally source.close()

    val json = parse(jsonString).getOrElse(Json.Null)
    json.asArray match
      case Some(array) => array.toList.flatMap(c => parseChallenge(c))
      case None => Nil

  private def parseChallenge(json: Json): Option[Challenge] =
    for {
      id          <- json.hcursor.get[Int]("id").toOption
      name        <- json.hcursor.get[String]("name").toOption
      description <- json.hcursor.get[String]("description").toOption
      fen         <- json.hcursor.get[String]("initialFen").toOption
      difficulty  <- json.hcursor.get[Int]("difficulty").toOption
      goalJson    <- json.hcursor.get[Json]("goal").toOption
      goal        <- parseGoal(goalJson)
    } yield Challenge(id, name, description, fen, goal, difficulty)

  private def parseGoal(json: Json): Option[ChallengeGoal] =
    val goalType = json.hcursor.get[String]("type").getOrElse("")
    goalType match
      case "mate" =>
        for {
          n     <- json.hcursor.get[Int]("n").toOption
          loserStr <- json.hcursor.get[String]("loser").toOption
          loser = if loserStr.toLowerCase == "white" then Color.White else Color.Black
        } yield MateGoal(n, loser)
      
      case "puzzle" =>
        for {
          moveStrings <- json.hcursor.get[List[String]]("moves").toOption
          moves = moveStrings.flatMap(parseMoveString)
        } yield PuzzleGoal(moves)

      case "reach" =>
        json.hcursor.get[String]("fen").toOption.map(ReachPositionGoal(_))

      case _ => None

  private def parseMoveString(s: String): Option[Move] =
    if s.length < 4 then None
    else
      for {
        from <- Pos.fromAlgebraic(s.substring(0, 2))
        to   <- Pos.fromAlgebraic(s.substring(2, 4))
        promotion = if s.length == 5 then parsePromotion(s(4)) else None
      } yield Move(from, to, promotion)

  private def parsePromotion(c: Char): Option[PieceType] =
    c.toLower match
      case 'q' => Some(PieceType.Queen)
      case 'r' => Some(PieceType.Rook)
      case 'b' => Some(PieceType.Bishop)
      case 'n' => Some(PieceType.Knight)
      case _   => None

  /**
   * Initializes the OdysseyState with loaded challenges and progress.
   */
  def initializeState(): OdysseyState =
    val challenges = loadChallenges()
    val completed = loadProgress()
    OdysseyState(challenges, completed)
