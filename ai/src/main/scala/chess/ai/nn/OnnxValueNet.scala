package chess.ai.nn

import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.Shape
import ai.djl.onnxruntime.engine.OrtEngine
import ai.djl.translate.{NoBatchifyTranslator, Translator, TranslatorContext}
import chess.model.{Color, GameState, Move, PieceType}

final case class OnnxValueOutput(value: Float)

private class OnnxValueTranslator extends Translator[Array[Float], OnnxValueOutput] with NoBatchifyTranslator[Array[Float], OnnxValueOutput]:
  override def processInput(ctx: TranslatorContext, input: Array[Float]): NDList =
    // Nexus Nano expects [1,12,8,8], DJL translator passes single sample tensor [12,8,8].
    NDList(ctx.getNDManager.create(input, new Shape(12, 8, 8)))

  override def processOutput(ctx: TranslatorContext, list: NDList): OnnxValueOutput =
    val raw = if list.size() > 0 then list.get(0).toFloatArray else Array.emptyFloatArray
    OnnxValueOutput(raw.headOption.getOrElse(0.0f))

  override def getBatchifier = null

class OnnxValueNet(
  modelPath: String
) extends PolicyValueNet:
  private val nnPawnScale: Double =
    sys.env.get("CHESS_NN_PAWN_SCALE").flatMap(_.toDoubleOption).getOrElse(1.0)

  private val criteria = ai.djl.repository.zoo.Criteria.builder()
    .setTypes(classOf[Array[Float]], classOf[OnnxValueOutput])
    .optModelPath(java.nio.file.Paths.get(modelPath))
    .optEngine(OrtEngine.ENGINE_NAME)
    .optTranslator(new OnnxValueTranslator())
    .build()

  private val model = criteria.loadModel()
  private val predictor = model.newPredictor()

  override def evaluate(state: GameState, legalMoves: List[Move]): PolicyValueEvaluation =
    val features = encode12(state)
    val out = predictor.predict(features)
    val nnValue = math.max(-1.0, math.min(1.0, out.value.toDouble))
    val value = math.max(-20.0, math.min(20.0, nnValue * nnPawnScale))
    val priors =
      if legalMoves.isEmpty then Map.empty[Move, Double]
      else
        val p = 1.0 / legalMoves.size.toDouble
        legalMoves.iterator.map(_ -> p).toMap
    PolicyValueEvaluation(priors = priors, value = value, uncertainty = 0.15)

  def close(): Unit =
    predictor.close()
    model.close()

  private def encode12(state: GameState): Array[Float] =
    val out = Array.fill[Float](12 * 8 * 8)(0.0f)
    state.board.foreachPiece { (pos, piece) =>
      val plane = piecePlane(piece.color, piece.pieceType)
      val idx = plane * 64 + pos.row * 8 + pos.col
      out(idx) = 1.0f
    }
    out

  private def piecePlane(color: Color, pieceType: PieceType): Int =
    val offset = if color == Color.White then 0 else 6
    val p = pieceType match
      case PieceType.Pawn => 0
      case PieceType.Knight => 1
      case PieceType.Bishop => 2
      case PieceType.Rook => 3
      case PieceType.Queen => 4
      case PieceType.King => 5
    offset + p

object OnnxValueNet:
  def fromPath(modelPath: String): OnnxValueNet = new OnnxValueNet(modelPath)
