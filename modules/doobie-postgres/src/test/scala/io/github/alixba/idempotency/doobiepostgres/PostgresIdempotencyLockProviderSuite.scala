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

import cats.effect.IO
import cats.effect.Resource
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.github.alixba.idempotency.IdempotencyLockProvider
import io.github.alixba.idempotency.IdempotencyLockProviderAbstractSuite

import scala.concurrent.duration.DurationInt

class PostgresIdempotencyLockProviderSuite
    extends IdempotencyLockProviderAbstractSuite
    with TestContainerForAll {
  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

  override def locker: Resource[IO, IdempotencyLockProvider[IO, String]] =
    withContainers { pgContainer =>
      Class.forName(pgContainer.driverClassName)

      HikariTransactor
        .newHikariTransactor[IO](
          driverClassName = pgContainer.driverClassName,
          url = pgContainer.jdbcUrl,
          user = pgContainer.username,
          pass = pgContainer.password,
          connectEC = ExecutionContexts.synchronous
        )
        .map(transactor =>
          PostgresIdempotencyLockProvider[IO, String](
            queries = PostgresIdempotencyLockProviderQueries[String],
            transactor = transactor,
            timeout = 500.millis
          )
        )
    }

  test("LockProvider should release lock on timeout") {
    locker.use { locker =>
      val io = for {
        error <- locker
          .lock("key")(IO.sleep(500.millis))
          .attempt
          .map(_.left.map(_.getMessage))
        result <- locker.lock("key")(IO.pure(42))
      } yield (error, result)

      val message =
        "FATAL: terminating connection due to idle-in-transaction timeout"

      assertIO(io, (Left(message), 42))
    }
  }

}
