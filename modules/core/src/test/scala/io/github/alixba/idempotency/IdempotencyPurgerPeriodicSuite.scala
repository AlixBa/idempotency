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

package io.github.alixba.idempotency

import cats.effect.IO
import cats.effect.Resource
import cats.implicits.none
import io.github.alixba.idempotency.IdempotencyPurger.IdempotencyPurger
import munit.CatsEffectSuite

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class IdempotencyPurgerPeriodicSuite extends CatsEffectSuite {

  @SuppressWarnings(Array("DisableSyntax.defaultArgs"))
  def store(purgeF: IO[Long] = IO.pure(0)): IdempotencyStore[IO, String, Int] =
    new IdempotencyStore[IO, String, Int] {
      val delegate: IdempotencyStore[IO, String, Int] =
        IdempotencyStore.inMemory[IO, String, Int]
      override def get(key: String): IO[Option[Int]] = delegate.get(key)
      override def put(key: String, value: Int): IO[Int] =
        delegate.put(key, value)
      override def put(key: String, value: Int, expiredAt: Instant): IO[Int] =
        delegate.put(key, value, expiredAt)
      override def delete(key: String): IO[Option[Int]] = delegate.delete(key)
      override def purge(): IO[Long] = purgeF
      override def isUniqueError(e: Throwable): Boolean =
        delegate.isUniqueError(e)
    }

  def purger(
      store: IdempotencyStore[IO, String, Int]
  ): Resource[IO, IdempotencyPurger[IO]] =
    IdempotencyPurger.periodic[IO, String, Int](
      locker = IdempotencyLockProvider
        .inMemory[IO, String],
      store = store,
      every = 100.millis,
      errorHandler = _ => IO.unit
    )

  test("PeriodicPurger should purge expired values") {
    val store =
      IdempotencyStore.inMemory[IO, String, Int]

    val io = for {
      now <- IO.realTimeInstant
      _ <- store.put("key", 42, now.plusMillis(100)).andWait(100.millis)
      _ <- purger(store).surround(IO.sleep(100.millis))
      result <- store.delete("key")
    } yield result

    assertIO(io, none)
  }

  test("PeriodicPurger should purge continuously") {
    val executions = new AtomicInteger(0)
    val store0 = store(IO.delay(executions.incrementAndGet().toLong))

    for {
      count <- purger(store0).surround(
        IO.delay(executions.get()).iterateUntil(_ >= 3)
      )
    } yield assert(count >= 3)
  }

  test("PeriodicPurger should not propagate errors") {
    val store0 = store(IO.raiseError(new Exception("fail")))
    val io = purger(store0).surround(IO.sleep(100.millis))
    assertIO(io, ())
  }

  test("PeriodicPurger should stop on demand") {
    val executions = new AtomicInteger(0)
    val store0 = store(IO.delay(executions.incrementAndGet().toLong))

    purger(store0).use { purger =>
      for {
        _ <- IO.delay(executions.get()).iterateUntil(_ > 0)
        _ <- purger.cancel
        before <- IO.delay(executions.get())
        _ <- IO.sleep(200.millis)
        after <- IO.delay(executions.get())
      } yield assertEquals(before, after)
    }
  }
}
