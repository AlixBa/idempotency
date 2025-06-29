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

import cats.Applicative
import cats.effect.kernel.Clock
import cats.effect.kernel.Sync
import cats.implicits.catsSyntaxApplicativeErrorId
import cats.implicits.catsSyntaxOptionId
import cats.syntax.applicative.*
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.option.none

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

trait IdempotencyStore[F[_], K, V] {

  /** Retrieves the current value of the store for the given key
    *
    * @return
    *   None in case the value doesn't exist or is expired. Some for a valid
    *   mapping
    */
  def get(key: K): F[Option[V]]

  /** Puts a new value for the key, without any expiration. The implementation
    * is expected to fail when the key is already mapping to a non expired
    * value.
    *
    * @return
    *   the value
    */
  def put(key: K, value: V): F[V]

  /** Same as [[put(key:K,value:V)*]] but the key has an expiration. Once
    * reached, the mapping should be deleted and/or not accessible through
    * [[get(key:K)*]]. Implementation should fail if expiredAt is in the past.
    *
    * @return
    *   the value
    */
  def put(key: K, value: V, expiredAt: Instant): F[V]

  /** Deletes a single key from the store
    *
    * @return
    *   the deleted value if existing
    */
  def delete(key: K): F[Option[V]]

  /** Removes all expired values from the store.
    *
    * @return
    *   the count of removed entries
    */
  def purge(): F[Long]

  /** Used by [[IdempotencyService]] to recover from concurrent
    * [[put(key:K,value:V)*]] or [[put(key:K,value:V,*]] if the
    * [[IdempotencyLockProvider]] didn't lock properly. Defined at this place as
    * the implementation of the store will dictate the rule. It makes more sense
    * to get all this logic at the same place.
    *
    * @return
    *   true if the error refers to a duplicated key error. false otherwise
    */
  def isUniqueError(e: Throwable): Boolean
}

object IdempotencyStore {

  final case class ExpiredAtException(now: Instant, expiredAt: Instant)
      extends RuntimeException(
        s"expiredAt should be after now. $expiredAt is not after $now"
      )

  def noop[F[_]: Applicative, K, V]: IdempotencyStore[F, K, V] =
    new IdempotencyStore[F, K, V] {
      override def get(key: K): F[Option[V]] = none.pure[F]
      override def put(key: K, value: V): F[V] = value.pure[F]
      override def put(key: K, value: V, expiredAt: Instant): F[V] =
        value.pure[F]
      override def delete(key: K): F[Option[V]] = none.pure[F]
      override def purge(): F[Long] = 0L.pure[F]
      override def isUniqueError(e: Throwable): Boolean = false
    }

  def inMemory[F[_]: Sync, K, V]: IdempotencyStore[F, K, V] =
    new IdempotencyStore[F, K, V] {
      private val store = new ConcurrentHashMap[K, (V, Option[Instant])]

      override def get(key: K): F[Option[V]] =
        Sync[F].delay(Option(store.get(key))).flatMap {
          case Some((v, Some(expiredAt))) =>
            Clock[F].realTimeInstant.map {
              case now if expiredAt.isAfter(now) => v.some
              case _                             => none
            }
          case Some((v, None)) => v.some.pure[F]
          case None            => none.pure[F]
        }

      override def put(key: K, value: V): F[V] =
        Clock[F].realTimeInstant.flatMap(now => put(key, value, now, none))

      override def put(key: K, value: V, expiredAt: Instant): F[V] =
        Clock[F].realTimeInstant.flatMap {
          case now if expiredAt.isAfter(now) =>
            put(key, value, now, expiredAt.some)
          case now => ExpiredAtException(now, expiredAt).raiseError
        }

      @SuppressWarnings(Array("DisableSyntax.throw", "DisableSyntax.null"))
      def put(
          key: K,
          value: V,
          now: Instant,
          expiredAt: Option[Instant]
      ): F[V] = Sync[F]
        .catchNonFatal(
          store.compute(
            key,
            {
              case (_, (_, None)) =>
                throw new Exception("Key already exists")
              case (_, (_, Some(expiredAt))) if expiredAt.isAfter(now) =>
                throw new Exception("Key already exists")
              case (_, (_, Some(_))) => (value, expiredAt)
              case (_, null)         => (value, expiredAt)
            }
          )
        )
        .map(_._1)

      override def delete(key: K): F[Option[V]] =
        Sync[F].delay(Option(store.remove(key)).map(_._1))

      override def purge(): F[Long] =
        Clock[F].realTimeInstant.flatMap { now =>
          for {
            before <- Sync[F].delay(store.size())
            _ <- Sync[F].delay(
              store
                .entrySet()
                .removeIf(entry =>
                  entry.getValue._2.exists(expiredAt => now.isAfter(expiredAt))
                )
            )
            after <- Sync[F].delay(store.size())
          } yield (before - after).toLong
        }

      override def isUniqueError(e: Throwable): Boolean =
        e.getMessage.equals("Key already exists")
    }

}
