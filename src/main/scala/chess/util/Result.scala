package chess.util

import scala.util.Try

/**
 * A custom Monad for functional error handling, similar to Either[String, A].
 */
sealed trait Result[+A] {
  def map[B](f: A => B): Result[B]
  def flatMap[B](f: A => Result[B]): Result[B]
  
  def isSuccess: Boolean
  def isFailure: Boolean
  
  def getOrElse[B >: A](default: => B): B
  
  def toEither: Either[String, A]
  def toOption: Option[A]
}

case class Success[+A](value: A) extends Result[A] {
  override def map[B](f: A => B): Result[B] = Success(f(value))
  override def flatMap[B](f: A => Result[B]): Result[B] = f(value)
  
  override def isSuccess: Boolean = true
  override def isFailure: Boolean = false
  
  override def getOrElse[B >: A](default: => B): B = value
  
  override def toEither: Either[String, A] = Right(value)
  override def toOption: Option[A] = Some(value)
}

case class Failure(message: String) extends Result[Nothing] {
  override def map[B](f: Nothing => B): Result[B] = this
  override def flatMap[B](f: Nothing => Result[B]): Result[B] = this
  
  override def isSuccess: Boolean = false
  override def isFailure: Boolean = true
  
  override def getOrElse[B >: Nothing](default: => B): B = default
  
  override def toEither: Either[String, Nothing] = Left(message)
  override def toOption: Option[Nothing] = None
}

object Result {
  def apply[A](value: A): Result[A] = Success(value)
  
  def fail(msg: String): Result[Nothing] = Failure(msg)
  
  def fromOption[A](option: Option[A], errorMsg: String): Result[A] =
    option match
      case Some(a) => Success(a)
      case None    => Failure(errorMsg)
      
  def fromEither[A](either: Either[String, A]): Result[A] =
    either match
      case Right(a) => Success(a)
      case Left(s)  => Failure(s)
      
  def fromTry[A](t: Try[A]): Result[A] =
    t match
      case scala.util.Success(a) => Success(a)
      case scala.util.Failure(e) => Failure(e.getMessage)
      
  def cond[A](test: Boolean, success: => A, failure: => String): Result[A] =
    if test then Success(success) else Failure(failure)
}
