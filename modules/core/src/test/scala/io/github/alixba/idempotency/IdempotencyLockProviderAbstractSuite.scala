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

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

trait IdempotencyLockProviderAbstractSuite extends CatsEffectSuite {

  def locker: Resource[IO, IdempotencyLockProvider[IO, String]]

  test("LockProvider should execute function") {
    locker.use { locker =>
      val io = for {
        result1 <- locker.lock("key")(IO.pure(42))
        result2 <- locker.lock("key")(IO.pure(42))
      } yield (result1, result2)

      assertIO(io, (42, 42))
    }
  }

  test("LockProvider should only execute function once for the same key") {
    locker.use { locker =>
      val startedExec = new AtomicInteger(0)
      val startedMax = new AtomicInteger(0)
      val ranExec = new AtomicInteger(0)
      val ranMax = new AtomicInteger(0)

      def task: IO[Unit] = for {
        _ <- IO.delay(ranExec.incrementAndGet())
        _ <- IO
          .delay(ranMax.set(math.max(ranMax.get(), ranExec.get())))
          .andWait(100.millis)
        _ <- IO.delay(ranExec.decrementAndGet())
      } yield ()

      def startTask: IO[Unit] = for {
        _ <- IO.delay(startedExec.incrementAndGet())
        _ <- IO.delay(
          startedMax.set(math.max(startedMax.get(), startedExec.get()))
        )
        _ <- locker.lock("key")(task)
        _ <- IO.delay(startedExec.decrementAndGet())
      } yield ()

      val io = List
        .fill(5)(startTask)
        .parSequence
        .map(_ => (startedMax.get(), ranMax.get()))

      assertIO(io, (5, 1))
    }
  }

  test("LockProvider should allow concurrent execution for different keys") {
    locker.use { locker =>
      val startedExec = new AtomicInteger(0)
      val startedMax = new AtomicInteger(0)
      val ranExec = new AtomicInteger(0)
      val ranMax = new AtomicInteger(0)

      def task: IO[Unit] = for {
        _ <- IO.delay(ranExec.incrementAndGet())
        _ <- IO
          .delay(ranMax.set(math.max(ranMax.get(), ranExec.get())))
          .andWait(100.millis)
        _ <- IO.delay(ranExec.decrementAndGet())
      } yield ()

      def startTask: IO[Unit] = for {
        _ <- IO.delay(startedExec.incrementAndGet())
        _ <- IO.delay(
          startedMax.set(math.max(startedMax.get(), startedExec.get()))
        )
        _ <- locker.lock("key")(task)
        _ <- IO.delay(startedExec.decrementAndGet())
      } yield ()

      val io = List
        .fill(5)(startTask)
        .parSequence
        .map(_ => (startedMax.get(), ranMax.get()))

      assertIO(io, (5, 1))
    }
  }

  test("LockProvider should propagate exceptions") {
    locker.use { locker =>
      val exception = new Exception("fail")
      val io = locker
        .lock("key")(IO.raiseError(exception))
        .attempt
        .map(_.left.map(_.getMessage))

      assertIO(io, Left("fail"))
    }
  }

  test("LockProvider should release lock when function fails") {
    locker.use { locker =>
      val io = for {
        _ <- locker
          .lock("key")(IO.raiseError(new Exception("fail")))
          .attempt
        result <- locker.lock("key")(IO.pure(42))
      } yield result

      assertIO(io, 42)
    }
  }
}
