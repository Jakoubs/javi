package chess.persistence.mongo

import org.bson.{BsonReader, BsonWriter, Document}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext, DocumentCodec}
import org.bson.codecs.configuration.{CodecProvider, CodecRegistries, CodecRegistry}

import chess.persistence.model.{MoveEvent, PersistedGame}

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

  // ─── CodecProvider & composite registry ───────────────────────────────────

  private object ChessCodecProvider extends CodecProvider:
    override def get[T](clazz: Class[T], r: CodecRegistry): Codec[T] | Null =
      if clazz == classOf[PersistedGame] then gameCodec.asInstanceOf[Codec[T]]
      else if clazz == classOf[MoveEvent] then moveEventCodec.asInstanceOf[Codec[T]]
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
