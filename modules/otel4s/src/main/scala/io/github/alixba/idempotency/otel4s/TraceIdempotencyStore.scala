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
import io.github.alixba.idempotency.IdempotencyStore
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.TracerProvider

import java.time.Instant

object TraceIdempotencyStore {

  def apply[F[_]: TracerProvider: Functor, K: Show, V](
      delegate: IdempotencyStore[F, K, V]
  ): F[IdempotencyStore[F, K, V]] = apply(
    delegate = delegate,
    getSpanName = "IdempotencyStore.get",
    putSpanName = "IdempotencyStore.put",
    deleteSpanName = "IdempotencyStore.delete",
    purgeSpanName = "IdempotencyStore.purge"
  )

  def apply[F[_]: TracerProvider: Functor, K: Show, V](
      delegate: IdempotencyStore[F, K, V],
      getSpanName: String,
      putSpanName: String,
      deleteSpanName: String,
      purgeSpanName: String
  ): F[IdempotencyStore[F, K, V]] =
    TracerProvider[F].get("io.github.alixba.idempotency").map { tracer =>
      new IdempotencyStore[F, K, V] {
        override def get(key: K): F[Option[V]] =
          tracer
            .span(getSpanName, Attribute("key", key.show))
            .surround(delegate.get(key))

        override def put(key: K, value: V): F[V] =
          tracer
            .span(putSpanName, Attribute("key", key.show))
            .surround(delegate.put(key, value))

        override def put(key: K, value: V, expiredAt: Instant): F[V] =
          tracer
            .span(putSpanName, Attribute("key", key.show))
            .surround(delegate.put(key, value, expiredAt))

        override def delete(key: K): F[Option[V]] =
          tracer
            .span(deleteSpanName, Attribute("key", key.show))
            .surround(delegate.delete(key))

        override def purge(): F[Long] =
          tracer.span(purgeSpanName).surround(delegate.purge())

        override def isUniqueError(e: Throwable): Boolean =
          delegate.isUniqueError(e)
      }
    }

}
