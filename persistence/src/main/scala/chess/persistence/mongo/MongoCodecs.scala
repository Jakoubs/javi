package chess.persistence.mongo

import org.bson.{BsonReader, BsonWriter, Document}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext, DocumentCodec}
import org.bson.codecs.configuration.{CodecProvider, CodecRegistries, CodecRegistry}

import chess.persistence.model.{MoveEvent, PersistedGame, Opening, Puzzle, PuzzleTheme, SavedGame}

/**
 * BSON codec helpers for [[PersistedGame]] and [[MoveEvent]].
 *
 * Uses hand-rolled codecs backed by `org.bson.Document` so the domain
 * model remains completely free of MongoDB annotations.
 *
 * The `defaultRegistry` is resolved once and then referenced from the
 * per-type codecs to avoid any initialisation-order cycles.
 */
object MongoCodecs:

  /** Default registry that knows how to encode/decode `Document` and all BSON primitives. */
  val defaultRegistry: CodecRegistry =
    CodecRegistries.fromCodecs(new DocumentCodec())

  // ─── PersistedGame codec ──────────────────────────────────────────────────

  private val gameCodec: Codec[PersistedGame] = new Codec[PersistedGame]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[PersistedGame] = classOf[PersistedGame]

    override def encode(
      writer: BsonWriter,
      value:  PersistedGame,
      ctx:    EncoderContext
    ): Unit =
      val doc = new Document()
        .append("_id",        value.id)
        .append("startFen",   value.startFen)
        .append("finalFen",   value.finalFen)
        .append("pgn",        value.pgn)
        .append("result",     value.result)
        .append("createdAt",  value.createdAt)
        .append("updatedAt",  value.updatedAt)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): PersistedGame =
      val doc = docCodec.decode(reader, ctx)
      PersistedGame(
        id        = doc.getString("_id"),
        startFen  = doc.getString("startFen"),
        finalFen  = doc.getString("finalFen"),
        pgn       = doc.getString("pgn"),
        result    = doc.getString("result"),
        createdAt = doc.getLong("createdAt"),
        updatedAt = doc.getLong("updatedAt")
      )

  // ─── MoveEvent codec ──────────────────────────────────────────────────────

  private val moveEventCodec: Codec[MoveEvent] = new Codec[MoveEvent]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[MoveEvent] = classOf[MoveEvent]

    override def encode(
      writer: BsonWriter,
      value:  MoveEvent,
      ctx:    EncoderContext
    ): Unit =
      val doc = new Document()
        .append("_id",        value.id)
        .append("gameId",     value.gameId)
        .append("moveNumber", value.moveNumber)
        .append("san",        value.san)
        .append("uci",        value.uci)
        .append("fenAfter",   value.fenAfter)
        .append("timestamp",  value.timestamp)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): MoveEvent =
      val doc = docCodec.decode(reader, ctx)
      MoveEvent(
        id         = doc.getString("_id"),
        gameId     = doc.getString("gameId"),
        moveNumber = doc.getInteger("moveNumber"),
        san        = doc.getString("san"),
        uci        = doc.getString("uci"),
        fenAfter   = doc.getString("fenAfter"),
        timestamp  = doc.getLong("timestamp")
      )

  // ─── Opening codec ────────────────────────────────────────────────────────

  private val openingCodec: Codec[Opening] = new Codec[Opening]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[Opening] = classOf[Opening]

    override def encode(
      writer: BsonWriter,
      value:  Opening,
      ctx:    EncoderContext
    ): Unit =
      val doc = new Document()
        .append("fen",    value.fen)
        .append("move",   value.move)
        .append("name",   value.name.orNull)
        .append("weight", value.weight)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): Opening =
      val doc = docCodec.decode(reader, ctx)
      Opening(
        fen    = doc.getString("fen"),
        move   = doc.getString("move"),
        name   = Option(doc.getString("name")),
        weight = doc.getInteger("weight")
      )

  // ─── Puzzle codec ─────────────────────────────────────────────────────────

  private val puzzleCodec: Codec[Puzzle] = new Codec[Puzzle]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[Puzzle] = classOf[Puzzle]

    override def encode(
      writer: BsonWriter,
      value:  Puzzle,
      ctx:    EncoderContext
    ): Unit =
      import scala.jdk.CollectionConverters.*
      val doc = new Document()
        .append("_id",      value.id)
        .append("fen",      value.fen)
        .append("solution", value.solution.asJava)
        .append("rating",   value.rating)
        .append("themes",   value.themes.asJava)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): Puzzle =
      import scala.jdk.CollectionConverters.*
      val doc = docCodec.decode(reader, ctx)
      Puzzle(
        id       = doc.getString("_id"),
        fen      = doc.getString("fen"),
        solution = doc.getList("solution", classOf[String]).asScala.toList,
        rating   = doc.getInteger("rating"),
        themes   = doc.getList("themes", classOf[String]).asScala.toList
      )

  // ─── PuzzleTheme codec ────────────────────────────────────────────────────

  private val puzzleThemeCodec: Codec[PuzzleTheme] = new Codec[PuzzleTheme]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[PuzzleTheme] = classOf[PuzzleTheme]

    override def encode(
      writer: BsonWriter,
      value:  PuzzleTheme,
      ctx:    EncoderContext
    ): Unit =
      val doc = new Document()
        .append("_id",         value.key)
        .append("name",        value.name)
        .append("description", value.description)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): PuzzleTheme =
      val doc = docCodec.decode(reader, ctx)
      PuzzleTheme(
        key         = doc.getString("_id"),
        name        = doc.getString("name"),
        description = doc.getString("description")
      )

  // ─── SavedGame codec ──────────────────────────────────────────────────────

  private val savedGameCodec: Codec[SavedGame] = new Codec[SavedGame]:
    private val docCodec = new DocumentCodec()

    override def getEncoderClass: Class[SavedGame] = classOf[SavedGame]

    override def encode(
      writer: BsonWriter,
      value:  SavedGame,
      ctx:    EncoderContext
    ): Unit =
      val doc = new Document()
        .append("_id",       value.id)
        .append("name",      value.name)
        .append("fen",       value.fen)
        .append("pgn",       value.pgn)
        .append("userId",    value.userId)
        .append("createdAt", value.createdAt.toEpochMilli)
      docCodec.encode(writer, doc, ctx)

    override def decode(
      reader: BsonReader,
      ctx:    DecoderContext
    ): SavedGame =
      val doc = docCodec.decode(reader, ctx)
      SavedGame(
        id        = doc.getString("_id"),
        name      = doc.getString("name"),
        fen       = doc.getString("fen"),
        pgn       = doc.getString("pgn"),
        userId    = doc.getLong("userId"),
        createdAt = java.time.Instant.ofEpochMilli(doc.getLong("createdAt"))
      )

  // ─── CodecProvider & composite registry ───────────────────────────────────

  private object ChessCodecProvider extends CodecProvider:
    override def get[T](clazz: Class[T], r: CodecRegistry): Codec[T] | Null =
      if clazz == classOf[PersistedGame] then gameCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[MoveEvent] then moveEventCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[Opening] then openingCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[Puzzle] then puzzleCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[PuzzleTheme] then puzzleThemeCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[SavedGame] then savedGameCodec.asInstanceOf[Codec[T]]
      else null

  /**
   * A [[CodecRegistry]] that handles both chess domain types and standard BSON types.
   * Pass this registry to a `MongoDatabase.withCodecRegistry(...)` call.
   */
  val registry: CodecRegistry =
    CodecRegistries.fromRegistries(
      CodecRegistries.fromProviders(ChessCodecProvider),
      defaultRegistry
    )
