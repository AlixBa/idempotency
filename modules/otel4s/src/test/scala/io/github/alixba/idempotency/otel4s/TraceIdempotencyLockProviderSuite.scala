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
import cats.implicits.catsSyntaxOptionId
import io.github.alixba.idempotency.IdempotencyLockProvider
import munit.CatsEffectSuite
import org.typelevel.otel4s.sdk.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.TracerProvider

import scala.concurrent.duration.DurationInt

class TraceIdempotencyLockProviderSuite extends CatsEffectSuite {

  test("LockProvider should time the lock function") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      implicit val provider: TracerProvider[IO] = testkit.tracerProvider
      val delegate = IdempotencyLockProvider.inMemory[IO, String]

      for {
        locker <- TraceIdempotencyLockProvider[IO, String](delegate)
        _ <- locker.lock("key")(IO.sleep(100.millis))
        traces <- testkit.finishedSpans
      } yield {
        assertEquals(traces.size, 1)

        val head = traces.head
        val attributes = head.attributes.elements.toVector
        val key = attributes.find(_.key.name == "key").map(_.value)
        val duration = head.endTimestamp.map(_ - head.startTimestamp)

        assertEquals(head.name, "IdempotencyLockProvider.lock")
        assertEquals(key, "key".some)
        assert(duration.map(_.toMillis).getOrElse(0L) > 100L)

      }
    }
  }

}
