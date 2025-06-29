## idempotency

A library providing idempotency support for non-idempotent APIs calls.

### Goal


Dealing with network calls (http, database, etc) is subject to failure. In some cases, you expect the call to be idempotent, meaning that no matter how much time you do the same call, you expect the same result. By wrapping network calls with an idempotent behavior, one can focus on the business logic of processes.


```scala mdoc
import cats.effect.IO
import cats.effect.Resource
import io.github.alixba.idempotency.IdempotencyService

// Let's assume an incoming request Req goes through 3 steps.
//
// We don't have any guarantee that we can retry indefinitely
// on a failed step, as the server could shut down, or it might
// need a proper fix before being resolved.
//
// But we need this `run` to reach the `save` step eventually.
def run[Req](
  getDataNonIdempotent: Req => IO[Int],
  callExternalService: Int => IO[Unit],
  save: (Req, Int) => IO[Unit]
): Req => IO[Unit] = (req: Req) => for {
  data <- getDataNonIdempotent(req)
  _ <- callExternalService(data)
  _ <- save(req, data)
} yield ()

// Some kind of HTTP server
def server[Req](useCase: Req => IO[Unit]): Resource[IO, Unit] = ???

def program[Req] = {
  val run0 = run[Req](
    getDataNonIdempotent = _ => IO.pure(1),
    callExternalService = _ => IO.unit,
    save = (_, _) => IO.unit
  )

  // basic retry until it succeeds.
  // not idempotent on each retry, so every call to `callExternalService`
  // can have a different input until `run` reaches a final successful state
  val run1 = (req: Req) => run0(req).recoverWith(_ => run0(req))

  server[Req](run1).use(_ => IO.never)
}

// With this simple implementation, since `getData` is not idempotent,
// each retry can result in a different final state. We can also imagine
// that we're making a PUT or POST HTTP request, and we don't want to
// create too many resources.

// By wrapping `getData` with an idempotent behavior, the retry is safer,
// and it doesn't leak in the implementation of `run`. By having a generic
// IdempotencyService, this behavior can easily be extended to all non-
// idempotent calls such as HTTP calls, SQL queries and many more.
def makeIdempotent[Req](
    service: IdempotencyService[IO, String, Int],
    key: Req => String,
    getDataNonIdempotent: Req => IO[Int]
): Req => IO[Int] = req => service.execute(key(req))(getDataNonIdempotent(req))
```

### Usage

This library is currently available for Scala binary versions 2.13 and 3.3+.
To use the latest version, include the following in your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.alixba" %% "idempotency-core" % "@VERSION@"
  "io.github.alixba" %% "idempotency-doobie-postgres" % "@VERSION@"
)
```
