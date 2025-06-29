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

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*

trait IdempotencyService[F[_], K, V] {

  /** Executes the provided function f in a key-based locked context. Stores the
    * result for future usage until it gets expired or deleted.
    */
  def execute(key: K)(f: F[V]): F[V]

}

object IdempotencyService {

  def apply[F[_]: MonadThrow, K, V](
      store: IdempotencyStore[F, K, V],
      locker: IdempotencyLockProvider[F, K]
  ): IdempotencyService[F, K, V] =
    concurrent(locker, idempotent(store))

  def noop[F[_]: MonadThrow, K, V]: IdempotencyService[F, K, V] =
    apply(
      IdempotencyStore.noop[F, K, V],
      IdempotencyLockProvider.noop[F, K]
    )

  def inMemory[F[_]: Async, K, V]: IdempotencyService[F, K, V] =
    apply(
      IdempotencyStore.inMemory[F, K, V],
      IdempotencyLockProvider.inMemory[F, K]
    )

  private def idempotent[F[_]: MonadThrow, K, V](
      store: IdempotencyStore[F, K, V]
  ): IdempotencyService[F, K, V] = new IdempotencyService[F, K, V] {
    def execute(key: K)(f: F[V]): F[V] = doExecute(key)(f).recoverWith {
      case error if store.isUniqueError(error) => doExecute(key)(f)
    }

    def doExecute(key: K)(f: F[V]): F[V] = store.get(key).flatMap {
      case Some(value) => value.pure[F]
      case None        => f.flatMap(store.put(key, _))
    }
  }

  private def concurrent[F[_], K, V](
      locker: IdempotencyLockProvider[F, K],
      service: IdempotencyService[F, K, V]
  ): IdempotencyService[F, K, V] = new IdempotencyService[F, K, V] {
    def execute(key: K)(f: F[V]): F[V] =
      // not perfect as we're locking on the underlying store.get
      // which could be avoided if the store already is set for this key.
      // to be improved in the future so we only lock on the f + store.put
      // and we register callers so we send them back f without doing it twice.
      locker.lock(key)(service.execute(key)(f))
  }

}
