package org.http4s.client.jdkhttpclient

import java.util.concurrent.Flow

/** An implementation of a JRE `Flow.Subscriber` which immediately cancels the
  * `Flow.Subscription`.
  *
  * We use this to clean up resources and conform the JRE API contract in
  * cases where the HTTP body is not read.
  */
private[jdkhttpclient] final class AlwaysCancelingSubscriber[A] extends Flow.Subscriber[A] {
  override def onSubscribe(subscription: Flow.Subscription): Unit = subscription.cancel

  override val onComplete: Unit = ()

  override def onError(throwable: Throwable): Unit = throw throwable

  override def onNext(item: A): Unit = ()
}
