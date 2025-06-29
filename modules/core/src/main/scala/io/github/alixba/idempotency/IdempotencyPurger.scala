/*
 * Copyright 2025 AlixBa
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

package io.github.alixba.idempotency

import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Resource
import cats.implicits.catsSyntaxFlatMapOps
import cats.syntax.applicativeError.catsSyntaxApplicativeError
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps

import scala.concurrent.duration.FiniteDuration

object IdempotencyPurger {

  type IdempotencyPurger[F[_]] = Fiber[F, Throwable, Unit]

  /** Creates an infinite non-failing background task calling
    * [[IdempotencyStore.purge()*]] periodically. Uses a
    * [[IdempotencyLockProvider]] to ensure only one execution at the same time
    */
  def periodic[F[_]: Async, K, V](
      locker: IdempotencyLockProvider[F, String],
      store: IdempotencyStore[F, K, V],
      every: FiniteDuration,
      errorHandler: Throwable => F[Unit]
  ): Resource[F, IdempotencyPurger[F]] = {
    val purge = locker
      .lock("IdempotencyPurger")(store.purge())
      .handleErrorWith(errorHandler(_).as(0))
      .flatMap(_ => Async[F].sleep(every))
      .foreverM[Unit]

    Resource.make(purge.start)(_.cancel)
  }

}
