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
import munit.CatsEffectSuite

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

trait IdempotencyServiceAbstractSuite extends CatsEffectSuite {

  def service: Resource[IO, IdempotencyService[IO, String, Int]]

  test("Service should execute function") {
    service.use { service =>
      assertIO(service.execute("key")(IO.pure(42)), 42)
    }
  }

  test("Service should cache result") {
    service.use { service =>
      val io = for {
        _ <- service.execute("key")(IO.pure(42))
        result <- service.execute("key")(IO.raiseError(new Exception("fail")))
      } yield result

      assertIO(io, 42)
    }
  }

  test("Service should propagate exceptions") {
    service.use { service =>
      val exception = new Exception("fail")
      val io = service.execute("key")(IO.raiseError(exception)).attempt
      assertIO(io, Left(exception))
    }
  }

  test("Service should retry on unique error") {
    service.use { service =>
      val uniqueError = new Exception("Key already exists")
      val ranOnce = new AtomicBoolean(false)

      val io = service.execute("key")(
        if (ranOnce.getAndSet(true)) IO.raiseError(uniqueError)
        else IO.pure(42)
      )

      assertIO(io, 42)
    }
  }

  test("Service should exclude concurrent executions") {
    service.use { service =>
      val startedExec = new AtomicInteger(0)
      val startedMax = new AtomicInteger(0)
      val ranExec = new AtomicInteger(0)
      val ranMax = new AtomicInteger(0)

      def task: IO[Int] = for {
        _ <- IO.delay(ranExec.incrementAndGet())
        _ <- IO
          .delay(ranMax.set(math.max(ranMax.get(), ranExec.get())))
          .andWait(100.millis)
        _ <- IO.delay(ranExec.decrementAndGet())
      } yield 42

      def startTask: IO[Int] = for {
        _ <- IO.delay(startedExec.incrementAndGet())
        _ <- IO.delay(
          startedMax.set(math.max(startedMax.get(), startedExec.get()))
        )
        result <- service.execute("key")(task)
        _ <- IO.delay(startedExec.decrementAndGet())
      } yield result

      val io = List
        .fill(5)(startTask)
        .parSequence
        .map(_ => (startedMax.get(), ranMax.get()))

      assertIO(io, (5, 1))
    }
  }
}
