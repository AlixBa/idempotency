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
import io.github.alixba.idempotency.IdempotencyService
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeterProvider

import java.util.concurrent.TimeUnit

object MetricIdempotencyService {

  // in seconds
  private val histogramsBuckets: BucketBoundaries =
    BucketBoundaries(.05, .1, .25, .5, 1, 1.5, 2, 2.5, 5)

  def apply[F[_]: MeterProvider: MonadCancelThrow, K, V](
      delegate: IdempotencyService[F, K, V]
  ): F[IdempotencyService[F, K, V]] =
    apply(
      delegate = delegate,
      executeHistogramName = "idempotency_service_execute_seconds",
      executeHistogramUnit = TimeUnit.SECONDS,
      executeHistogramBuckets = histogramsBuckets
    )

  def apply[F[_]: MeterProvider: MonadCancelThrow, K, V](
      delegate: IdempotencyService[F, K, V],
      executeHistogramName: String,
      executeHistogramUnit: TimeUnit,
      executeHistogramBuckets: BucketBoundaries
  ): F[IdempotencyService[F, K, V]] = {
    for {
      meter <- MeterProvider[F].get("io.github.alixba.idempotency")
      executeHistogram <- meter
        .histogram[Double](executeHistogramName)
        .withUnit(executeHistogramUnit.name().toLowerCase)
        .withDescription("IdempotencyService.execute latency")
        .withExplicitBucketBoundaries(executeHistogramBuckets)
        .create
    } yield new IdempotencyService[F, K, V] {
      override def execute(key: K)(f: F[V]): F[V] =
        executeHistogram
          .recordDuration(executeHistogramUnit)
          .surround(delegate.execute(key)(f))
    }
  }

}
