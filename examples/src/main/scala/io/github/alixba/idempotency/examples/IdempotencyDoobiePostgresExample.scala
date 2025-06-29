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

package io.github.alixba.idempotency.examples

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import doobie.Meta
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.github.alixba.idempotency.IdempotencyService
import io.github.alixba.idempotency.doobiepostgres.PostgresIdempotencyLockProvider
import io.github.alixba.idempotency.doobiepostgres.PostgresIdempotencyLockProviderQueries
import io.github.alixba.idempotency.doobiepostgres.PostgresIdempotencyStore
import io.github.alixba.idempotency.doobiepostgres.PostgresIdempotencyStoreQueries
import io.github.alixba.idempotency.otel4s.MetricIdempotencyLockProvider
import io.github.alixba.idempotency.otel4s.MetricIdempotencyService
import io.github.alixba.idempotency.otel4s.MetricIdempotencyStore
import io.github.alixba.idempotency.otel4s.TraceIdempotencyLockProvider
import io.github.alixba.idempotency.otel4s.TraceIdempotencyService
import io.github.alixba.idempotency.otel4s.TraceIdempotencyStore
import org.postgresql.util.PGobject
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.oteljava.context.IOLocalContextStorage
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.TracerProvider

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import scala.concurrent.duration.DurationInt

// scalafmt: { maxColumn: 120 }
object IdempotencyDoobiePostgresExample extends IOApp.Simple {

  type K = String
  case class V(json: String)

  // rely on doobie-postgres-circe for this
  implicit val meta: Meta[V] = Meta.Advanced
    .other[PGobject]("jsonb")
    .imap[String](_.getValue)(s => {
      val obj = new PGobject
      obj.setType("jsonb")
      obj.setValue(s)
      obj
    })
    .imap[V](V(_))(_.json)

  // assuming there is a postgres server and an OTel backend available
  // see doobie-postgres-example-docker-compose.yml
  override def run: IO[Unit] = {
    implicit val lp: LocalProvider[IO, Context] =
      IOLocalContextStorage.localProvider[IO]

    OtelJava
      .autoConfigured[IO]()
      .use { otel =>
        implicit val tp: TracerProvider[IO] = otel.tracerProvider
        implicit val mp: MeterProvider[IO] = otel.meterProvider

        (for {
          transactor <- transactor()
          http <- Resource.fromAutoCloseable(IO.delay(HttpClient.newHttpClient()))
          // LockProvider
          queriesLocker = PostgresIdempotencyLockProviderQueries[K]
          locker = PostgresIdempotencyLockProvider[IO, K](queriesLocker, transactor, 1.second)
          lockerT <- TraceIdempotencyLockProvider[IO, K](locker).toResource
          lockerM <- MetricIdempotencyLockProvider[IO, K](lockerT).toResource
          // Store
          queriesStore = PostgresIdempotencyStoreQueries[K, V]()
          store = PostgresIdempotencyStore[IO, K, V](queriesStore, transactor)
          storeT <- TraceIdempotencyStore[IO, K, V](store).toResource
          storeM <- MetricIdempotencyStore[IO, K, V](storeT).toResource
          // Service
          service = IdempotencyService[IO, K, V](storeM, lockerM)
          serviceT <- TraceIdempotencyService[IO, K, V](service).toResource
          serviceM <- MetricIdempotencyService[IO, K, V](serviceT).toResource
          // Init DB
          _ <- store.initialize.toResource
        } yield (http, serviceM)).use { case (http, idempotency) =>
          tp.get("io.github.alixba.idempotency.example").flatMap { implicit tracer =>
            tracer.rootSpan("IdempotencyDoobiePostgresExample").surround {
              val key = "doobie-postgres-example"
              List.fill(3)(idempotency.execute(key)(nonIdempotentHttpCall(http))).parSequence
            }
          }
        }
      }
      /*
       * -- LOGS
       * [info] Got random value xNhPim
       * [info] Finished with values List({"value": "xNhPim"}, ..., {"value": "xNhPim"})
       *
       * -- DATABASE
       * postgres@0.0.0.0:idempotency> SELECT * FROM idempotency_store;
       * +-------------------------+---------------------+-------------------------------+
       * | key                     | value               | created_at                    |
       * |-------------------------+---------------------+-------------------------------|
       * | doobie-postgres-example | {"value": "xNhPim"} | 2025-06-29 13:12:38.831319+00 |
       * +-------------------------+---------------------+-------------------------------+
       */
      .flatMap(values => IO.println(s"Finished with values ${values.map(_.json)}"))
  }

  private def transactor(): Resource[IO, HikariTransactor[IO]] = for {
    ec <- ExecutionContexts.fixedThreadPool[IO](3)
    xa <- HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = "jdbc:postgresql://localhost:5432/idempotency",
      user = "postgres",
      pass = "postgres",
      connectEC = ec
    )
  } yield xa

  private def nonIdempotentHttpCall(client: HttpClient)(implicit tracer: Tracer[IO]): IO[V] = {
    val host = "http://www.randomnumberapi.com"
    val path = "/api/v1.0/randomstring?min=5&max=10&count=1"
    val req = HttpRequest.newBuilder(URI.create(host + path)).GET().build()

    tracer.span("HttpCall").surround {
      IO
        .fromCompletableFuture(IO(client.sendAsync(req, BodyHandlers.ofString())))
        // formatted as an array: ["sMierZwjgeBTS"]
        .map(_.body().filter(_.isLetterOrDigit))
        .flatTap(random => IO.println(s"Got random value $random"))
        .map(random => V(s"""{"value": "$random"}"""))
    }
  }

}
