package akka.streams

import scala.language.implicitConversions
import rx.async.api.{ Consumer, Producer }

sealed trait Operation[-I, +O]

object Operation {
  type ==>[-I, +O] = Operation[I, O] // brevity alias (should we mark it `private`?)

  type Source[+O] = Unit ==> O
  type Sink[-I] = I ==> Unit

  implicit def fromIterable[T](iterable: Iterable[T]) = FromIterableSource(iterable)
  case class FromIterableSource[T](iterable: Iterable[T]) extends Source[T]

  implicit def fromProducer[T](producer: Producer[T]) = FromProducerSource(producer)
  case class FromProducerSource[T](producer: Producer[T]) extends Source[T]

  implicit def fromConsumer[T](consumer: Consumer[T]) = FromConsumerSink(consumer)
  case class FromConsumerSink[T](consumer: Consumer[T]) extends Sink[T]

  case class ExposeProducer[T]() extends (Source[T] ==> Producer[T])
  // implicit def sink2Producer[T](sink: Sink[T]): Producer[T] = ???
  // implicit def source2Consumer[T](source: Source[T]): Consumer[T] = ???

  def apply[A, B, C](f: A ==> B, g: B ==> C): A ==> C =
    (f, g) match {
      case (Identity(), _) ⇒ g.asInstanceOf[A ==> C]
      case (_, Identity()) ⇒ f.asInstanceOf[A ==> C]
      case _               ⇒ AndThen(f, g)
    }

  // basic operation composition
  // consumes and produces no faster than the respective minimum rates of f and g
  case class AndThen[A, B, C](f: A ==> B, g: B ==> C) extends (A ==> C)

  // adds (bounded or unbounded) pressure elasticity
  // consumes at max rate as long as `canConsume` is true,
  // produces no faster than the rate with which `expand` produces B values
  case class Buffer[A, B, S](seed: S,
                             compress: (S, A) ⇒ S,
                             expand: S ⇒ (S, Option[B]),
                             canConsume: S ⇒ Boolean) extends (A ==> B)

  // "compresses" a fast upstream by keeping one element buffered and reducing surplus values using the given function
  // consumes at max rate, produces no faster than the upstream
  def Compress[T](f: (T, T) ⇒ T): T ==> T =
    Buffer[T, T, Option[T]](
      seed = None,
      compress = (s, x) ⇒ s.map(f(_, x)) orElse Some(x),
      expand = None -> _,
      canConsume = _ ⇒ true)

  // drops the first n upstream values
  // consumes the first n upstream values at max rate, afterwards directly copies upstream
  def Drop[T](n: Int): T ==> T =
    FoldUntil[T, T, Int](
      seed = n,
      onNext = (n, x) ⇒ if (n <= 0) FoldUntil.Emit(x, 0) else FoldUntil.Continue(n - 1),
      onComplete = _ ⇒ None)

  // produces one boolean for the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Exists[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (p(x)) Some(true) else None, Some(false))

  // "expands" a slow upstream by buffering the last upstream element and producing it whenever requested
  // consumes at max rate, produces at max rate once the first upstream value has been buffered
  def Expand[T, S](seed: S, produce: S ⇒ (S, T)): T ==> T =
    Buffer[T, T, Option[T]](
      seed = None,
      compress = (_, x) ⇒ Some(x),
      expand = s ⇒ s -> s,
      canConsume = _ ⇒ true)

  // filters a streams according to the given predicate
  // immediately consumes more whenever p(t) is false
  def Filter[T](p: T ⇒ Boolean): T ==> T =
    FoldUntil[T, T, Unit](
      seed = (),
      onNext = (_, x) ⇒ if (p(x)) FoldUntil.Emit(x, ()) else FoldUntil.Continue(()),
      onComplete = _ ⇒ None)

  // produces the first T that satisfies p
  // consumes at max rate until p(t) becomes true, unsubscribes afterwards
  def Find[T](p: T ⇒ Boolean): T ==> T =
    MapFind[T, T](x ⇒ if (p(x)) Some(x) else None, None)

  // general flatmap operation
  // consumes no faster than the downstream, produces no faster than upstream or generated sources
  def FlatMap[A, B](f: A ⇒ Source[B]): A ==> B =
    Map(f).flatten

  // flattens the upstream by concatenation
  // consumes no faster than the downstream, produces no faster than the sources in the upstream
  case class Flatten[T]() extends (Source[T] ==> T)

  // classic fold
  // consumes at max rate, produces only one value
  def Fold[A, B](seed: B, f: (B, A) ⇒ B): A ==> B =
    FoldUntil[A, B, B]( // TODO: while this representation is correct it's also slower than a direct implementation
      seed,
      onNext = (b, a) ⇒ FoldUntil.Continue(f(b, a)),
      onComplete = Some(_))

  // generalized fold potentially producing several output values
  // consumes at max rate as long as `onNext` returns `Continue`
  // produces no faster than the upstream
  case class FoldUntil[A, B, S](seed: S,
                                onNext: (S, A) ⇒ FoldUntil.Command[B, S],
                                onComplete: S ⇒ Option[B]) extends (A ==> B)
  object FoldUntil {
    sealed trait Command[+T, +S]
    case class Emit[T, S](value: T, nextState: S) extends Command[T, S]
    case class EmitAndStop[T, S](value: T) extends Command[T, S]
    case class Continue[T, S](nextState: S) extends Command[T, S]
    case object Stop extends Command[Nothing, Nothing]
  }

  // produces one boolean (if all upstream values satisfy p emits true otherwise false)
  // consumes at max rate until p(t) becomes false, unsubscribes afterwards
  def ForAll[T](p: T ⇒ Boolean): T ==> Boolean =
    MapFind[T, Boolean](x ⇒ if (!p(x)) Some(false) else None, Some(true))

  // sinks all upstream value into the given function
  // consumes at max rate
  case class Foreach[T](f: T ⇒ Unit) extends Sink[T]

  // produces the first upstream element, unsubscribes afterwards
  def Head[T](): T ==> T = Take(1)

  // maps the upstream onto itself
  case class Identity[A]() extends (A ==> A)

  // maps the given function over the upstream
  // does not affect consumption or production rates
  case class Map[A, B](f: A ⇒ B) extends (A ==> B)

  // produces the first B returned by f or optionally the given default value
  // consumes at max rate until f returns a Some, unsubscribes afterwards
  def MapFind[A, B](f: A ⇒ Option[B], default: ⇒ Option[B]): A ==> B =
    FoldUntil[A, B, Unit](
      seed = (),
      onNext = (_, x) ⇒ f(x).fold[FoldUntil.Command[B, Unit]](FoldUntil.Continue(()))(FoldUntil.EmitAndStop(_)),
      onComplete = _ ⇒ default)

  // merges the values produced by the given source into the consumed stream
  // consumes from the upstream and the given source no faster than the downstream
  // produces no faster than the combined rate from upstream and the given source
  case class Merge[A, B](source: Source[B]) extends (A ==> B)

  // splits the upstream into sub-streams based on the given predicate
  // if p evaluates to true the current value is appended to the previous sub-stream,
  // otherwise the previous sub-stream is closed and a new one started
  // consumes and produces no faster than the produced sources are consumed
  case class Span[T](p: T ⇒ Boolean) extends (T ==> Source[T])

  // taps into the upstream and forwards all incoming values also into the given sink
  // consumes no faster than the minimum rate of the downstream and the given sink
  case class Tee[T](sink: Sink[T]) extends (T ==> T)

  // drops the first upstream value and forwards the remaining upstream
  // consumes the first upstream value immediately, afterwards directly copies upstream
  def Tail[T](): T ==> T = Drop(1)

  // forwards the first n upstream values, unsubscribes afterwards
  // consumes no faster than the downstream, produces no faster than the upstream
  def Take[T](n: Int): T ==> T =
    FoldUntil[T, T, Int](
      seed = n,
      onNext = (n, x) ⇒ n match {
        case _ if n <= 0 ⇒ FoldUntil.Stop
        case 1           ⇒ FoldUntil.EmitAndStop(x)
        case _           ⇒ FoldUntil.Emit(x, n - 1)
      },
      onComplete = _ ⇒ None)

  // combines the upstream and the given source into tuples
  // produces at the rate of the slower upstream (i.e. no values are dropped)
  // consumes from the upstream no faster than the downstream consumption rate or the production rate of the given source
  // consumes from the given source no faster than the downstream consumption rate or the upstream production rate
  case class Zip[A, B, C](source: Source[C]) extends (A ==> (B, C))

  implicit def producer2Ops1[T](producer: Producer[T]) = Ops1[Unit, T](producer)
  implicit def sourceOps1[T](source: Source[T]) = Ops1[Unit, T](source)
  implicit def producerOps2[I, O](op: I ==> Producer[O]): Ops2[I, O] = Ops2(Ops1(op).map(FromProducerSource(_)))
  implicit def sourceOps2[T](source: Source[Source[T]]) = Ops2[Unit, T](source)

  implicit class Ops1[A, B](val op: A ==> B) extends AnyVal {
    def andThen[C](op: B ==> C): A ==> C = Operation(this.op, op)
    def buffer[C, S](seed: S)(compress: (S, B) ⇒ S)(expand: S ⇒ (S, Option[C]))(canConsume: S ⇒ Boolean): A ==> C = andThen(Buffer(seed, compress, expand, canConsume))
    def compress(f: (B, B) ⇒ B): A ==> B = andThen(Compress(f))
    def drop(n: Int): A ==> B = andThen(Drop(n))
    def exists(p: B ⇒ Boolean): A ==> Boolean = andThen(Exists(p))
    def expand[S](seed: S)(produce: S ⇒ (S, B)): A ==> B = andThen(Expand(seed, produce))
    def filter(p: B ⇒ Boolean): A ==> B = andThen(Filter(p))
    def find(p: B ⇒ Boolean): A ==> B = andThen(Find(p))
    def flatMap[C](f: B ⇒ Source[C]): A ==> C = andThen(FlatMap(f))
    def fold[C](seed: C)(f: (C, B) ⇒ C): A ==> C = andThen(Fold(seed, f))
    def foldUntil[S, C](seed: S)(f: (S, B) ⇒ FoldUntil.Command[C, S])(onComplete: S ⇒ Option[C]): A ==> C = andThen(FoldUntil(seed, f, onComplete))
    def forAll(p: B ⇒ Boolean): A ==> Boolean = andThen(ForAll(p))
    def foreach(f: B ⇒ Unit): Sink[A] = andThen(Foreach(f))
    def head: A ==> B = andThen(Head())
    def map[C](f: B ⇒ C): A ==> C = andThen(Map(f))
    def mapFind[C](f: B ⇒ Option[C], default: ⇒ Option[C]): A ==> C = andThen(MapFind(f, default))
    def merge(source: Source[B]): A ==> B = andThen(Merge(source))
    def span(p: B ⇒ Boolean): A ==> Source[B] = andThen(Span(p))
    def tee(sink: Sink[B]): A ==> B = andThen(Tee(sink))
    def tail: A ==> B = andThen(Tail())
    def take(n: Int): A ==> B = andThen(Take[B](n))
    def zip[C](source: Source[C]): A ==> (B, C) = andThen(Zip(source))
  }

  implicit class Ops2[A, B](val op: A ==> Source[B]) extends AnyVal {
    def flatten: A ==> B = Operation(op, Flatten[B]())
    def expose: A ==> Producer[B] = Operation(op, ExposeProducer())
  }
}