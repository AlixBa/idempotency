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

package io.github.alixba.idempotency.otel4s

import cats.Functor
import cats.Show
import cats.implicits.toFunctorOps
import cats.implicits.toShow
import io.github.alixba.idempotency.IdempotencyLockProvider
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.TracerProvider

object TraceIdempotencyLockProvider {

  def apply[F[_]: TracerProvider: Functor, K: Show](
      delegate: IdempotencyLockProvider[F, K]
  ): F[IdempotencyLockProvider[F, K]] =
    apply(delegate, "IdempotencyLockProvider.lock")

  def apply[F[_]: TracerProvider: Functor, K: Show](
      delegate: IdempotencyLockProvider[F, K],
      spanName: String
  ): F[IdempotencyLockProvider[F, K]] =
    TracerProvider[F].get("io.github.alixba.idempotency").map { tracer =>
      new IdempotencyLockProvider[F, K] {
        override def lock[A](key: K)(f: F[A]): F[A] =
          tracer
            .span(spanName, Attribute("key", key.show))
            .surround(delegate.lock(key)(f))
      }
    }

}
