/*
 * Copyright 2021 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.jdkhttpclient

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
