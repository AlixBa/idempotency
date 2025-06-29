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

import cats.effect.MonadCancelThrow
import cats.effect.implicits.monadCancelOps_
import cats.syntax.applicativeError.catsSyntaxApplicativeError
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import doobie.ConnectionIO
import doobie.FC
import doobie.Transactor
import io.github.alixba.idempotency.IdempotencyLockProvider

import scala.concurrent.duration.FiniteDuration

object PostgresIdempotencyLockProvider {

  def apply[F[_]: MonadCancelThrow, K](
      queries: PostgresIdempotencyLockProviderQueries[K],
      transactor: Transactor[F],
      timeout: FiniteDuration
  ): IdempotencyLockProvider[F, K] = {
    new IdempotencyLockProvider[F, K] {
      def lock[A](key: K)(f: F[A]): F[A] = {
        import transactor.*

        // basically a ConnectionIO[A].transact(transactor)
        // but we keep the hand on the connection so we can
        // do a two-phase lock without losing it at pg level
        connect(kernel).use { conn =>
          def run[B](cio: ConnectionIO[B]): F[B] =
            cio.foldMap(interpret).run(conn)

          val lock = run(for {
            _ <- FC.setAutoCommit(false)
            _ <- queries.setTimeout(timeout).run
            _ <- queries.getLock(key).unique
          } yield ())
          val commit = run(FC.commit)
          val rollback = run(FC.rollback).orElse(MonadCancelThrow[F].unit)

          (for {
            _ <- lock
            result <- f
            _ <- commit
          } yield result)
            .onError(_ => rollback)
            .onCancel(rollback)
        }
      }
    }
  }

}
