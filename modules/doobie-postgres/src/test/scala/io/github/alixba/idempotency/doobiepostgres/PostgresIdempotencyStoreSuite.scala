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
import doobie.Transactor
import io.github.alixba.idempotency.IdempotencyStore
import io.github.alixba.idempotency.IdempotencyStoreAbstractSuite

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration.DurationInt

class PostgresIdempotencyStoreSuite
    extends IdempotencyStoreAbstractSuite
    with TestContainerForAll {
  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

  val purgeLimit: Int = 5

  override def store: Resource[IO, IdempotencyStore[IO, String, Int]] =
    withContainers { pgContainer =>
      Class.forName(pgContainer.driverClassName)

      val transactor = Transactor.fromConnection[IO](
        connection = DriverManager.getConnection(
          pgContainer.jdbcUrl,
          pgContainer.username,
          pgContainer.password
        ),
        logHandler = None
      )

      val store = PostgresIdempotencyStore[IO, String, Int](
        queries = PostgresIdempotencyStoreQueries[String, Int](
          tableName = "idempotency_store_" + UUID.randomUUID().toString,
          keyType = "text",
          valueType = "smallint"
        ),
        transactor = transactor,
        purgeLimit = purgeLimit
      )

      store.initialize.as(store).toResource
    }

  test("Store should recurse while there are entries to purge") {
    store.use { store =>
      val io = for {
        now <- IO.realTimeInstant
        _ <- List
          .tabulate(purgeLimit * 2)(i =>
            store.put(s"key$i", 42, now.plusMillis(100))
          )
          .parSequence
          .andWait(100.millis)
        result <- store.purge()
      } yield result

      assertIO(io, purgeLimit * 2L)
    }
  }

}
