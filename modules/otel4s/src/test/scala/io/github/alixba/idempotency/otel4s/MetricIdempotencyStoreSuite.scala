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

import cats.effect.IO
import cats.implicits.none
import io.github.alixba.idempotency.IdempotencyStore
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

import java.time.Instant
import scala.concurrent.duration.DurationInt

class MetricIdempotencyStoreSuite extends CatsEffectSuite {

  def testMetric[A](
      functionName: String,
      metricName: String
  )(io: IdempotencyStore[IO, String, Int] => IO[A]): Unit = {
    test(s"Store should time the $functionName function") {
      MetricsTestkit.inMemory[IO]().use { testkit =>
        implicit val provider: MeterProvider[IO] = testkit.meterProvider
        val delegate = new IdempotencyStore[IO, String, Int] {
          override def get(key: String): IO[Option[Int]] =
            IO.sleep(100.millis).as(none)
          override def put(key: String, value: Int): IO[Int] =
            IO.sleep(100.millis).as(value)
          override def put(key: String, value: Int, expiredAt: Instant)
              : IO[Int] = IO.sleep(100.millis).as(value)
          override def delete(key: String): IO[Option[Int]] =
            IO.sleep(100.millis).as(none)
          override def purge(): IO[Long] = IO.sleep(100.millis).as(0)
          override def isUniqueError(e: Throwable): Boolean = false
        }

        for {
          store <- MetricIdempotencyStore[IO, String, Int](delegate)
          _ <- io(store)
          metrics <- testkit.collectMetrics
        } yield {
          assertEquals(metrics.size, 1)

          val metric = metrics.head
          val point = metric.data.points.head
          val duration = point.timeWindow.end - point.timeWindow.start

          assertEquals(metric.name, metricName)
          assert(duration.toMillis >= 100L)
        }
      }
    }
  }

  testMetric("get", "idempotency_store_get_seconds")(_.get("key"))

  testMetric("put", "idempotency_store_put_seconds")(_.put("key", 1))

  testMetric("put with expiredAt", "idempotency_store_put_seconds")(store =>
    for {
      now <- IO.realTimeInstant
      _ <- store.put("key", 1, now.plusMillis(100))
    } yield ()
  )

  testMetric("delete", "idempotency_store_delete_seconds")(_.delete("key"))

  testMetric("purge", "idempotency_store_purge_seconds")(_.purge())

}
