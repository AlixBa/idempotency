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

import cats.effect.MonadCancelThrow
import cats.implicits.toFlatMapOps
import cats.implicits.toFunctorOps
import io.github.alixba.idempotency.IdempotencyStore
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeterProvider

import java.time.Instant
import java.util.concurrent.TimeUnit

object MetricIdempotencyStore {

  // in seconds
  private val histogramsBuckets: BucketBoundaries =
    BucketBoundaries(.05, .1, .25, .5, 1)

  def apply[F[_]: MeterProvider: MonadCancelThrow, K, V](
      delegate: IdempotencyStore[F, K, V]
  ): F[IdempotencyStore[F, K, V]] = apply(
    delegate = delegate,
    getHistogramName = "idempotency_store_get_seconds",
    getHistogramUnit = TimeUnit.SECONDS,
    getHistogramBuckets = histogramsBuckets,
    putHistogramName = "idempotency_store_put_seconds",
    putHistogramUnit = TimeUnit.SECONDS,
    putHistogramBuckets = histogramsBuckets,
    deleteHistogramName = "idempotency_store_delete_seconds",
    deleteHistogramUnit = TimeUnit.SECONDS,
    deleteHistogramBuckets = histogramsBuckets,
    purgeHistogramName = "idempotency_store_purge_seconds",
    purgeHistogramUnit = TimeUnit.SECONDS,
    purgeHistogramBuckets = histogramsBuckets
  )

  def apply[F[_]: MeterProvider: MonadCancelThrow, K, V](
      delegate: IdempotencyStore[F, K, V],
      getHistogramName: String,
      getHistogramUnit: TimeUnit,
      getHistogramBuckets: BucketBoundaries,
      putHistogramName: String,
      putHistogramUnit: TimeUnit,
      putHistogramBuckets: BucketBoundaries,
      deleteHistogramName: String,
      deleteHistogramUnit: TimeUnit,
      deleteHistogramBuckets: BucketBoundaries,
      purgeHistogramName: String,
      purgeHistogramUnit: TimeUnit,
      purgeHistogramBuckets: BucketBoundaries
  ): F[IdempotencyStore[F, K, V]] = {
    for {
      meter <- MeterProvider[F].get("io.github.alixba.idempotency")
      getHistogram <- meter
        .histogram[Double](getHistogramName)
        .withUnit(getHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyStore.get latency")
        .withExplicitBucketBoundaries(getHistogramBuckets)
        .create
      putHistogram <- meter
        .histogram[Double](putHistogramName)
        .withUnit(putHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyStore.put latency")
        .withExplicitBucketBoundaries(putHistogramBuckets)
        .create
      deleteHistogram <- meter
        .histogram[Double](deleteHistogramName)
        .withUnit(deleteHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyStore.delete latency")
        .withExplicitBucketBoundaries(deleteHistogramBuckets)
        .create
      purgeHistogram <- meter
        .histogram[Double](purgeHistogramName)
        .withUnit(purgeHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyStore.purge latency")
        .withExplicitBucketBoundaries(purgeHistogramBuckets)
        .create
    } yield new IdempotencyStore[F, K, V] {
      override def get(key: K): F[Option[V]] =
        getHistogram
          .recordDuration(getHistogramUnit)
          .surround(delegate.get(key))

      override def put(key: K, value: V): F[V] =
        putHistogram
          .recordDuration(putHistogramUnit)
          .surround(delegate.put(key, value))

      override def put(key: K, value: V, expiredAt: Instant): F[V] =
        putHistogram
          .recordDuration(putHistogramUnit)
          .surround(delegate.put(key, value, expiredAt))

      override def delete(key: K): F[Option[V]] =
        deleteHistogram
          .recordDuration(deleteHistogramUnit)
          .surround(delegate.delete(key))

      override def purge(): F[Long] =
        purgeHistogram
          .recordDuration(purgeHistogramUnit)
          .surround(delegate.purge())

      override def isUniqueError(e: Throwable): Boolean =
        delegate.isUniqueError(e)
    }
  }

}
