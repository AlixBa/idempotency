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

import cats.Show
import cats.implicits.toShow
import doobie.Fragment
import doobie.Query0
import doobie.Update0
import doobie.implicits.toSqlInterpolator

import scala.concurrent.duration.FiniteDuration

trait PostgresIdempotencyLockProviderQueries[K] {
  def setTimeout(timeout: FiniteDuration): Update0
  def getLock(key: K): Query0[Unit]
}

object PostgresIdempotencyLockProviderQueries {

  def apply[K: Show]: PostgresIdempotencyLockProviderQueries[K] =
    new PostgresIdempotencyLockProviderQueries[K] {
      override def setTimeout(timeout: FiniteDuration): Update0 = {
        val timeoutMs = Fragment.const0(timeout.toMillis.toString)
        sql"SET LOCAL idle_in_transaction_session_timeout = $timeoutMs".update
      }

      override def getLock(key: K): Query0[Unit] =
        sql"SELECT pg_advisory_xact_lock(('x' || md5(${key.show}))::bit(64)::bigint)"
          .query[Unit]
    }

}
