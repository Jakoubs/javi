package chess.util

trait Observer[T]:
  def update(state: T): Unit

trait Observable[T]:
  private var subscribers: Vector[Observer[T]] = Vector.empty
  def addObserver(s: Observer[T]): Unit = subscribers = subscribers :+ s
  def removeObserver(s: Observer[T]): Unit =
    val index = subscribers.indexOf(s)
    if index != -1 then subscribers = subscribers.patch(index, Nil, 1)
  def notifyObservers(state: T): Unit = subscribers.foreach(_.update(state))
