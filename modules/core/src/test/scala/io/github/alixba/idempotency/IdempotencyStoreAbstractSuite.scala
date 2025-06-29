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
import cats.implicits.catsSyntaxOptionId
import cats.implicits.none
import io.github.alixba.idempotency.IdempotencyStore.ExpiredAtException
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

trait IdempotencyStoreAbstractSuite extends CatsEffectSuite {

  def store: Resource[IO, IdempotencyStore[IO, String, Int]]

  test("Store should return None for non-existent keys") {
    store.use { store =>
      assertIO(store.get("key"), none)
    }
  }

  test("Store should store and retrieve values") {
    store.use { store =>
      for {
        result1 <- store.put("key", 42)
        result2 <- store.get("key")
      } yield {
        assertEquals(result1, 42)
        assertEquals(result2, 42.some)
      }
    }
  }

  test("Store should not retrieve an expired value") {
    store.use { store =>
      val io = for {
        now <- IO.realTimeInstant
        _ <- store.put("key", 42, now.plusMillis(100)).andWait(100.millis)
        result <- store.get("key")
      } yield result

      assertIO(io, none)
    }
  }

  test("Store should not update existing values") {
    store.use { store =>
      val io = for {
        _ <- store.put("key", 42)
        left <- store.put("key", 100).attempt
      } yield left.swap.map(store.isUniqueError).getOrElse(false)

      assertIO(io, true)
    }
  }

  test("Store should update an expired value") {
    store.use { store =>
      val io = for {
        now <- IO.realTimeInstant
        _ <- store.put("key", 42, now.plusMillis(100)).andWait(100.millis)
        result <- store.put("key", 100)
      } yield result

      assertIO(io, 100)
    }
  }

  test("Store should fail storing an expired value") {
    store.use { store =>
      val io = for {
        now <- IO.realTimeInstant
        left <- store.put("key", 42, now).attempt
      } yield left.swap.map(_.getClass.getName).getOrElse("") + "$"

      assertIO(io, ExpiredAtException.getClass.getName)
    }
  }

  test("Store should return None deleting a non existing key") {
    store.use { store =>
      assertIO(store.delete("key"), none)
    }
  }

  test("Store should return the value on delete") {
    store.use { store =>
      val io = for {
        _ <- store.put("key", 42)
        result <- store.delete("key")
      } yield result

      assertIO(io, 42.some)
    }
  }

  test("Store should return 0 when purge has no effect") {
    store.use { store =>
      assertIO(store.purge(), 0L)
    }
  }

  test(
    "Store should purge expired values and return the count of purged entries"
  ) {
    store.use { store =>
      for {
        _ <- store.put("key1", 42)
        now <- IO.realTimeInstant
        _ <- store.put("key2", 42, now.plusMillis(100)).andWait(100.millis)
        purge <- store.purge()
        result1 <- store.get("key1")
        result2 <- store.get("key2")
      } yield {
        assertEquals(purge, 1L)
        assertEquals(result1, 42.some)
        assertEquals(result2, none)
      }
    }
  }

}
