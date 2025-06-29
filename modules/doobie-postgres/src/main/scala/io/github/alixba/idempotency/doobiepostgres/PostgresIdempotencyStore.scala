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

package io.github.alixba.idempotency.doobiepostgres

import cats.effect.kernel.Async
import cats.effect.kernel.Clock
import cats.implicits.catsSyntaxApplicativeErrorId
import cats.implicits.catsSyntaxApplicativeId
import cats.implicits.toFlatMapOps
import cats.implicits.toFunctorOps
import doobie.Transactor
import doobie.syntax.connectionio.toConnectionIOOps
import io.github.alixba.idempotency.IdempotencyStore
import io.github.alixba.idempotency.IdempotencyStore.ExpiredAtException

import java.time.Instant

trait PostgresIdempotencyStore[F[_], K, V] extends IdempotencyStore[F, K, V] {

  /** Initializes the database with the table and the indexes used by the store
    * to get, put and delete mappings.
    */
  def initialize: F[Unit]
}

object PostgresIdempotencyStore {

  def apply[F[_]: Async, K, V](
      queries: PostgresIdempotencyStoreQueries[K, V],
      transactor: Transactor[F]
  ): PostgresIdempotencyStore[F, K, V] =
    apply(queries = queries, transactor = transactor, purgeLimit = 1000)

  def apply[F[_]: Async, K, V](
      queries: PostgresIdempotencyStoreQueries[K, V],
      transactor: Transactor[F],
      purgeLimit: Int
  ): PostgresIdempotencyStore[F, K, V] =
    new PostgresIdempotencyStore[F, K, V] {
      override def get(key: K): F[Option[V]] =
        queries.get(key).option.transact(transactor)

      override def put(key: K, value: V): F[V] =
        queries.put(key, value).unique.transact(transactor)

      override def put(key: K, value: V, expiredAt: Instant): F[V] =
        Clock[F].realTimeInstant.flatMap {
          case now if !expiredAt.isAfter(now) =>
            ExpiredAtException(now, expiredAt).raiseError
          case _ =>
            queries.put(key, value, expiredAt).unique.transact(transactor)
        }

      override def delete(key: K): F[Option[V]] =
        queries.delete(key).option.transact(transactor)

      override def purge(): F[Long] = {
        queries.purge(purgeLimit).run.transact(transactor).flatMap {
          case i if i < purgeLimit => i.toLong.pure[F]
          case _                   => purge().map(_ + purgeLimit)
        }
      }

      override def isUniqueError(e: Throwable): Boolean =
        queries.isUniqueError(e)

      override def initialize: F[Unit] = (for {
        _ <- queries.createTable.run
        _ <- queries.createIndex.run
      } yield ()).transact(transactor)

    }

}
