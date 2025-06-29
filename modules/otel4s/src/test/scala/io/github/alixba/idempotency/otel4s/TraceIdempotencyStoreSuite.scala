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
import org.typelevel.otel4s.sdk.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.TracerProvider

import java.time.Instant
import scala.concurrent.duration.DurationInt

class TraceIdempotencyStoreSuite extends CatsEffectSuite {

  def testTrace[A](
      functionName: String,
      spanName: String,
      attributes: Map[String, String]
  )(io: IdempotencyStore[IO, String, Int] => IO[A]): Unit = {
    test(s"Store should time the $functionName function") {
      TracesTestkit.inMemory[IO]().use { testkit =>
        implicit val provider: TracerProvider[IO] = testkit.tracerProvider
        val delegate = new IdempotencyStore[IO, String, Int] {
          override def get(key: String): IO[Option[Int]] =
            IO.sleep(100.millis).as(none)
          override def put(key: String, value: Int): IO[Int] =
            IO.sleep(100.millis).as(value)
          override def put(key: String, value: Int, expiredAt: Instant)
              : IO[Int] = IO.sleep(100.millis).as(value)
          override def delete(key: String): IO[Option[Int]] =
            IO.sleep(100.millis).as(none)
          override def purge(): IO[Long] = IO.sleep(100.millis).as(0L)
          override def isUniqueError(e: Throwable): Boolean = false
        }

        for {
          store <- TraceIdempotencyStore[IO, String, Int](delegate)
          _ <- io(store)
          traces <- testkit.finishedSpans
        } yield {
          assertEquals(traces.size, 1)

          val span = traces.head
          val duration = span.endTimestamp.map(_ - span.startTimestamp)
          val _attributes = span.attributes.elements.toVector
            .map(a => a.key.name -> a.value.toString)
            .toMap

          assertEquals(span.name, spanName)
          assert(duration.map(_.toMillis).getOrElse(0L) >= 100L)
          assertEquals(_attributes, attributes)
        }
      }
    }
  }

  testTrace(
    functionName = "get",
    spanName = "IdempotencyStore.get",
    attributes = Map("key" -> "key")
  )(_.get("key"))

  testTrace(
    functionName = "put",
    spanName = "IdempotencyStore.put",
    attributes = Map("key" -> "key")
  )(_.put("key", 42))

  testTrace(
    functionName = "put with expiredAt",
    spanName = "IdempotencyStore.put",
    attributes = Map("key" -> "key")
  )(store =>
    for {
      now <- IO.realTimeInstant
      _ <- store.put("key", 42, now.plusMillis(100))
    } yield ()
  )

  testTrace(
    functionName = "delete",
    spanName = "IdempotencyStore.delete",
    attributes = Map("key" -> "key")
  )(_.delete("key"))

  testTrace(
    functionName = "purge",
    spanName = "IdempotencyStore.purge",
    attributes = Map.empty
  )(_.purge())

}
