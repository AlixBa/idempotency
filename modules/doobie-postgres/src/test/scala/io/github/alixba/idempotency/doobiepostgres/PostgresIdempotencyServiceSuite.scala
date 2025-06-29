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
import io.github.alixba.idempotency.IdempotencyService
import io.github.alixba.idempotency.IdempotencyServiceAbstractSuite

import java.util.UUID
import scala.concurrent.duration.DurationInt

class PostgresIdempotencyServiceSuite
    extends IdempotencyServiceAbstractSuite
    with TestContainerForAll {
  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

  def service: Resource[IO, IdempotencyService[IO, String, Int]] =
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
        .evalMap { transactor =>
          val locker = PostgresIdempotencyLockProvider[IO, String](
            queries = PostgresIdempotencyLockProviderQueries[String],
            transactor = transactor,
            timeout = 500.millis
          )

          val store = PostgresIdempotencyStore[IO, String, Int](
            queries = PostgresIdempotencyStoreQueries[String, Int](
              tableName = "idempotency_store_" + UUID.randomUUID().toString,
              keyType = "text",
              valueType = "smallint"
            ),
            transactor = transactor
          )

          val service = IdempotencyService[IO, String, Int](
            store = store,
            locker = locker
          )

          store.initialize.as(service)
        }
    }
}
