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

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.std.Semaphore
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import java.util.concurrent.ConcurrentHashMap

trait IdempotencyLockProvider[F[_], K] {

  /** Executes the provided function f in a key-based locked context.
    *
    * This means that implementations should guarantee only one execution of f
    * at a given time, in a best effort basis, for a given key. This is because
    * it's assumed that idempotent requests are expected to be run as few as
    * possible.
    *
    * When using a distributed lock, implementations should take care about
    * releasing the lock and take all outcome into consideration (success,
    * error, timeout, etc.).
    */
  def lock[A](key: K)(f: F[A]): F[A]

}

object IdempotencyLockProvider {

  def noop[F[_], K]: IdempotencyLockProvider[F, K] =
    new IdempotencyLockProvider[F, K] {
      def lock[A](key: K)(f: F[A]): F[A] = f
    }

  def inMemory[F[_]: Async, K]: IdempotencyLockProvider[F, K] =
    new IdempotencyLockProvider[F, K] {
      // TODO purge this to avoid growing infinitely
      val locks: ConcurrentHashMap[K, Semaphore[F]] =
        new ConcurrentHashMap[K, Semaphore[F]]()

      def lock[A](key: K)(f: F[A]): F[A] =
        for {
          default <- Semaphore[F](1)
          semaphore <- Sync[F].delay(locks.computeIfAbsent(key, _ => default))
          result <- semaphore.permit.surround(f)
        } yield result
    }

}
