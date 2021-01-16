package org.http4s.client.jdkhttpclient

import java.util.concurrent.Flow

/** An implementation of a JRE `Flow.Subscriber` which immediately cancels the
  * `Flow.Subscription`.
  *
  * We use this to clean up resources and conform the JRE API contract in
  * cases where the HTTP body is not read.
  */
private[jdkhttpclient] final class AlwaysCancelingSubscriber[A] extends Flow.Subscriber[A] {
  override def onSubscribe(subscription: Flow.Subscription): Unit =
    subscription.cancel

  override def onComplete(): Unit =
    throw new IllegalStateException(
      "AlwaysCancelingSubscriber onComplete was invoked. This should never occur as only onSubscribe should be called."
    )

  override def onError(throwable: Throwable): Unit =
    throw new IllegalStateException(
      "AlwaysCancelingSubscriber onError was invoked. This should never occur as only onSubscribe should be called.",
      throwable
    )

  override def onNext(item: A): Unit =
    throw new IllegalStateException(
      "AlwaysCancelingSubscriber onNext was invoked. This should never occur as only onSubscribe should be called."
    )
}
