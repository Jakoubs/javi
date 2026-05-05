package chess.ai2.nn

import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.Shape
import ai.djl.onnxruntime.engine.OrtEngine
import ai.djl.translate.{NoBatchifyTranslator, Translator, TranslatorContext}
import chess.model.{Color, GameState, Move}

import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, TimeUnit}

final case class OnnxOutput(policy: Array[Float], value: Float)

private class OnnxPolicyValueTranslator(policySize: Int)
    extends Translator[Array[Float], OnnxOutput]
    with NoBatchifyTranslator[Array[Float], OnnxOutput]:

  override def processInput(ctx: TranslatorContext, input: Array[Float]): NDList =
    val nd = ctx.getNDManager.create(input, new Shape(FeatureEncoder.PlaneCount, 8, 8))
    NDList(nd)

  override def processOutput(ctx: TranslatorContext, list: NDList): OnnxOutput =
    val policyRaw = list.get(0).toFloatArray
    val valueRaw = list.get(1).toFloatArray

    val policy =
      if policyRaw.length >= policySize then policyRaw.take(policySize)
      else
        val out = Array.fill[Float](policySize)(0.0f)
        System.arraycopy(policyRaw, 0, out, 0, policyRaw.length)
        out

    OnnxOutput(policy = policy, value = valueRaw.headOption.getOrElse(0.0f))

  override def getBatchifier = null

private final case class InferenceRequest(
  state: GameState,
  legalMoves: List[Move],
  promise: CompletableFuture[PolicyValueEvaluation]
)

class OnnxPolicyValueNet(
  modelPath: String,
  moveIndexer: MoveIndexer = new FastMoveIndexer(),
  valueBlendHce: Double = 0.25,
  maxBatchSize: Int = 16,
  maxWaitMicros: Long = 800L,
  queueCapacity: Int = 4096
) extends PolicyValueNet:

  private val criteria = ai.djl.repository.zoo.Criteria.builder()
    .setTypes(classOf[Array[Float]], classOf[OnnxOutput])
    .optModelPath(java.nio.file.Paths.get(modelPath))
    .optEngine(OrtEngine.ENGINE_NAME)
    .optTranslator(new OnnxPolicyValueTranslator(moveIndexer.policySize))
    .build()

  private val model = criteria.loadModel()
  private val predictor = model.newPredictor()

  private val queue = ArrayBlockingQueue[InferenceRequest](queueCapacity)
  @volatile private var running = true

  private val dispatcher = Thread(() => dispatchLoop(), "onnx-dispatcher")
  dispatcher.setDaemon(true)
  dispatcher.start()

  override def evaluate(state: GameState, legalMoves: List[Move]): PolicyValueEvaluation =
    val promise = CompletableFuture[PolicyValueEvaluation]()
    val req = InferenceRequest(state, legalMoves, promise)
    if !queue.offer(req) then runInferenceSingle(state, legalMoves)
    else promise.get()

  override def evaluateBatch(inputs: List[(GameState, List[Move])]): List[PolicyValueEvaluation] =
    inputs.map { case (state, legalMoves) => evaluate(state, legalMoves) }

  def close(): Unit =
    running = false
    dispatcher.interrupt()
    predictor.close()
    model.close()

  private def dispatchLoop(): Unit =
    val batch = new java.util.ArrayList[InferenceRequest](maxBatchSize)
    while running do
      try
        val first = queue.poll(50, TimeUnit.MILLISECONDS)
        if first != null then
          batch.clear()
          batch.add(first)

          val start = System.nanoTime()
          var gather = true
          while gather && batch.size() < maxBatchSize do
            val elapsedUs = (System.nanoTime() - start) / 1000L
            if elapsedUs >= maxWaitMicros then gather = false
            else
              val next = queue.poll(50, TimeUnit.MICROSECONDS)
              if next == null then gather = false else batch.add(next)

          runBatch(batch)
      catch
        case _: InterruptedException => ()

  private def runBatch(batch: java.util.ArrayList[InferenceRequest]): Unit =
    val features = new java.util.ArrayList[Array[Float]](batch.size())
    var i = 0
    while i < batch.size() do
      features.add(FeatureEncoder.encode(batch.get(i).state))
      i += 1

    val outputs = predictor.batchPredict(features)

    i = 0
    while i < batch.size() do
      val req = batch.get(i)
      val out = outputs.get(i)
      req.promise.complete(toEvaluation(req.state, req.legalMoves, out))
      i += 1

  private def runInferenceSingle(state: GameState, legalMoves: List[Move]): PolicyValueEvaluation =
    val out = predictor.predict(FeatureEncoder.encode(state))
    toEvaluation(state, legalMoves, out)

  private def toEvaluation(state: GameState, legalMoves: List[Move], out: OnnxOutput): PolicyValueEvaluation =
    val priors = buildMovePriors(out.policy, legalMoves)
    val nnValue = math.max(-1.0, math.min(1.0, out.value.toDouble))

    val side = if state.activeColor == Color.White then 1.0 else -1.0
    val hce = math.tanh((chess.ai.Evaluator.evaluate(state) * side) / 600.0)
    val value = nnValue * (1.0 - valueBlendHce) + hce * valueBlendHce

    PolicyValueEvaluation(priors, value, uncertainty = 0.1)

  private def buildMovePriors(policy: Array[Float], legalMoves: List[Move]): Map[Move, Double] =
    if legalMoves.isEmpty then Map.empty
    else
      val pairs = legalMoves.iterator.map { move =>
        val idx = moveIndexer.indexOf(move)
        val raw = if idx >= 0 && idx < policy.length then policy(idx).toDouble else 0.0
        move -> math.max(raw, 1e-8)
      }.toArray

      val sum = pairs.foldLeft(0.0)((acc, x) => acc + x._2)
      if sum <= 0 then
        val uniform = 1.0 / legalMoves.size.toDouble
        legalMoves.iterator.map(_ -> uniform).toMap
      else
        pairs.iterator.map { case (m, s) => m -> (s / sum) }.toMap

object OnnxPolicyValueNet:
  def fromPath(modelPath: String): OnnxPolicyValueNet = new OnnxPolicyValueNet(modelPath)

