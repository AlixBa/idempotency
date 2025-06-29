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

import cats.implicits.catsSyntaxOptionId
import cats.implicits.none
import doobie.Query0
import doobie.Read
import doobie.implicits.toSqlInterpolator
import doobie.util.Write
import doobie.util.fragment.Fragment
import doobie.util.meta.Meta
import doobie.util.update.Update0

import java.time.Instant
import scala.annotation.unused

trait PostgresIdempotencyStoreQueries[K, V] {
  def get(key: K): Query0[V]
  def put(key: K, value: V): Query0[V]
  def put(key: K, value: V, expiredAt: Instant): Query0[V]
  def delete(key: K): Query0[V]
  def purge(limit: Int): Update0

  def isUniqueError(e: Throwable): Boolean

  def createTable: Update0
  def createIndex: Update0
}

object PostgresIdempotencyStoreQueries {

  def apply[K: Write, V: Read: Write](
  ): PostgresIdempotencyStoreQueries[K, V] =
    apply("idempotency_store", "text", "jsonb")

  def apply[K: Write, V: Read: Write](
      tableName: String,
      keyType: String,
      valueType: String
  ): PostgresIdempotencyStoreQueries[K, V] =
    new PostgresIdempotencyStoreQueries[K, V] {
      val tn0: Fragment = Fragment.const0(tableName)
      val kt0: Fragment = Fragment.const0(keyType)
      val vt0: Fragment = Fragment.const0(valueType)

      @unused implicit val i: Meta[Instant] =
        doobie.postgres.implicits.JavaInstantMeta

      override def get(key: K): Query0[V] =
        sql"""
          SELECT "value"
          FROM "$tn0"
          WHERE "key" = $key AND ("expired_at" IS NULL OR "expired_at" > NOW())
        """.query[V]

      override def put(key: K, value: V): Query0[V] =
        put(key, value, none)

      override def put(key: K, value: V, expiredAt: Instant): Query0[V] =
        put(key, value, expiredAt.some)

      def put(key: K, value: V, expiredAt: Option[Instant]): Query0[V] =
        sql"""
          INSERT INTO "$tn0" ("key", "value", "created_at", "expired_at")
          VALUES ($key, $value, NOW(), $expiredAt)
          ON CONFLICT ("key") DO UPDATE
          SET "value" = $value, "created_at" = NOW(), "expired_at" = $expiredAt
          WHERE "$tn0"."expired_at" IS NOT NULL AND "$tn0"."expired_at" <= NOW()
          RETURNING "value"
        """.query[V]

      override def delete(key: K): Query0[V] =
        sql"""
          DELETE FROM "$tn0"
          WHERE "key" = $key
          RETURNING "value"
        """.query[V]

      override def purge(limit: Int): Update0 =
        sql"""
          WITH ids AS (
            SELECT "key" FROM "$tn0"
            WHERE "expired_at" IS NOT NULL AND "expired_at" <= NOW()
            FOR UPDATE
            LIMIT $limit
          )
          DELETE FROM "$tn0"
          USING "ids"
          WHERE "$tn0"."key" = "ids"."key";
        """.update

      override def isUniqueError(e: Throwable): Boolean = {
        // using ON CONFLICT will not raise a
        // `ERROR: duplicate key value violates unique constraint`
        // anymore. The ResultSet will not contain any row, and we have a
        // `ResultSet exhausted; more rows expected.` instead
        e.getMessage.equals("ResultSet exhausted; more rows expected.")
      }

      override def createTable: Update0 =
        sql"""
          CREATE TABLE IF NOT EXISTS "$tn0" (
            "key"        $kt0        NOT NULL PRIMARY KEY,
            "value"      $vt0        NOT NULL,
            "created_at" TIMESTAMPTZ NOT NULL,
            "expired_at" TIMESTAMPTZ NULL
          )
        """.update

      override def createIndex: Update0 =
        sql"""
          CREATE INDEX IF NOT EXISTS "${tn0}_expired_at_idx" ON "$tn0"("expired_at")
        """.update

    }

}
