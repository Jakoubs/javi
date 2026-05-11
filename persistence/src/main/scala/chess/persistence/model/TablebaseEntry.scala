package chess.persistence.model

final case class TablebaseEntry(
  fen: String,
  bestMove: String,
  wdl: Int,
  dtz: Option[Int],
  dtm: Option[Int],
  source: String
)

