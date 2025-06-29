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
import io.github.alixba.idempotency.IdempotencyLockProvider
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeterProvider

import java.util.concurrent.TimeUnit

object MetricIdempotencyLockProvider {

  // in seconds
  private val histogramsBuckets: BucketBoundaries =
    BucketBoundaries(.05, .1, .25, .5, 1, 1.5, 2, 2.5, 5)

  def apply[F[_]: MeterProvider: MonadCancelThrow, K](
      delegate: IdempotencyLockProvider[F, K]
  ): F[IdempotencyLockProvider[F, K]] =
    apply(
      delegate = delegate,
      lockHistogramName = "idempotency_lock_provider_lock_seconds",
      lockHistogramUnit = TimeUnit.SECONDS,
      lockHistogramBuckets = histogramsBuckets
    )

  def apply[F[_]: MeterProvider: MonadCancelThrow, K](
      delegate: IdempotencyLockProvider[F, K],
      lockHistogramName: String,
      lockHistogramUnit: TimeUnit,
      lockHistogramBuckets: BucketBoundaries
  ): F[IdempotencyLockProvider[F, K]] = {
    for {
      meter <- MeterProvider[F].get("io.github.alixba.idempotency")
      lockHistogram <- meter
        .histogram[Double](lockHistogramName)
        .withUnit(lockHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyLockProvider.lock latency")
        .withExplicitBucketBoundaries(lockHistogramBuckets)
        .create
    } yield new IdempotencyLockProvider[F, K] {
      override def lock[A](key: K)(f: F[A]): F[A] =
        lockHistogram
          .recordDuration(lockHistogramUnit)
          .surround(delegate.lock(key)(f))
    }
  }

}
