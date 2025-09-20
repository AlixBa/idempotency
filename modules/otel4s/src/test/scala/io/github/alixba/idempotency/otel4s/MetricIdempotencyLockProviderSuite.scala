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
import io.github.alixba.idempotency.IdempotencyLockProvider
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

import scala.concurrent.duration.DurationInt

class MetricIdempotencyLockProviderSuite extends CatsEffectSuite {

  test("LockProvider should time the lock function") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      implicit val provider: MeterProvider[IO] = testkit.meterProvider
      val delegate = IdempotencyLockProvider.inMemory[IO, String]

      for {
        locker <- MetricIdempotencyLockProvider[IO, String](delegate)
        _ <- locker.lock("key")(IO.sleep(100.millis))
        metrics <- testkit.collectMetrics
      } yield {
        assertEquals(metrics.size, 1)

        val head = metrics.head
        val point = head.data.points.head
        val duration = point.timeWindow.end - point.timeWindow.start

        assertEquals(head.name, "idempotency_lock_provider_lock_seconds")
        assert(duration.toMillis >= 100)
      }
    }
  }

}
