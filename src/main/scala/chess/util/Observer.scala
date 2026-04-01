package chess.util

trait Observer[T]:
  def update(state: T): Unit

trait Observable[T]:
  private var subscribers: Vector[Observer[T]] = Vector.empty
  def addObserver(s: Observer[T]): Unit = subscribers = subscribers :+ s
  def removeObserver(s: Observer[T]): Unit = subscribers = subscribers.filterNot(_ == s)
  def notifyObservers(state: T): Unit = subscribers.foreach(_.update(state))
