# micrometer-mixed-histogram-shape

A Prometheus scrape that fails with a `ClassCastException` because one meter of a family carries no
histogram while its siblings do.

```
java.lang.ClassCastException: class io.prometheus.metrics.model.snapshots.SummarySnapshot$SummaryDataPointSnapshot
  cannot be cast to class io.prometheus.metrics.model.snapshots.HistogramSnapshot$HistogramDataPointSnapshot
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeClassicHistogramBuckets(...)
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeHistogram(...)
```

The scrape endpoint then answers HTTP 500. It delivers no families at all, not even the healthy ones,
so the process disappears from monitoring without going red anywhere. Only a restart clears it.

## Versions

Spring Boot 4.1.0, Micrometer 1.17.0, `prometheus-metrics-exposition-textformats` 1.5.1. The behaviour
is not new to these versions; the same failure was observed on Spring Boot 3.x.

## Run it

```bash
mvn test
```

All three tests pass. The one that reproduces the defect asserts that the scrape throws.

## What the tests show

### 1. `baselineScrapesCleanly`

The healthy case. `PropertiesMeterFilter` is installed before any meter exists, so every timer of
`http.server.requests` receives the service level objectives from
`management.metrics.distribution.slo[http.server.requests]`. Every series carries the same 17 buckets
and the scrape succeeds.

### 2. `aMeterRegisteredBeforeTheFilterKeepsItsEmptyShapeForever`

One timer registers before the filter is installed. It receives an empty `DistributionStatisticConfig`
and therefore no histogram. The filter is installed afterwards, and the remaining timers of the same
family receive their buckets as usual.

From then on the family carries two shapes, and that state is permanent:

* `MeterRegistry.Config.meterFilter` adds every existing pre-filter id to `stalePreFilterIds`.
* On the next registration of the same id, `getOrCreateMeter` finds the id stale, maps it again, finds
  the existing meter under the mapped id, calls `unmarkStaleId` and returns **that same meter**.
* The distribution configuration is never recomputed, because the filter loop sits in the branch that
  only runs when no meter exists yet.

The test asserts the identity directly: the `Timer` returned after the filter was installed is the
same object as the one created before it, and it still reports no histogram counts. Both exposition
formats then throw.

So the warning that Micrometer logs here is not advisory. For a Prometheus registry it predicts a
permanently broken scrape:

```
A MeterFilter is being configured after a Meter has been registered to this registry. ...
let the Micrometer maintainers know at https://github.com/micrometer-metrics/micrometer/issues/4920.
```

### 3. `concurrentRegistrationIsSafe`

A negative control. 32 threads register 25 ids of the same family at once, with the filter installed
from the start. Nothing is lost. This is expected, because `getOrCreateMeter` applies the filters
inside `synchronized (meterMapLock)` and `MeterRegistry.filters` is `volatile`. The test exists so
that a concurrency explanation can be ruled out rather than assumed.

## Why this matters beyond the warning

One meter without a histogram does not degrade one series. It removes the whole scrape. A library that
treats the mixed shape as a configuration mistake still leaves the operator with a 500 and no
indication of which series caused it, because the exposition writer reports the cast, not the name.

Two directions would both help:

* Let the exposition writer skip or report the offending family instead of failing the whole response.
* Rebuild the meter when a late filter marks its id stale, instead of unmarking the id and returning
  the meter that the filter never configured.

## Field notes from production

The failure was observed on two environments of the same service, on different endpoints and different
status codes, which is why the affected series looks arbitrary:

| Environment | series |
| --- | --- |
| DEV | `http_server_requests_seconds{method=GET, status=400, outcome=CLIENT_ERROR, uri=/analytics/v1/timelines}` |
| PROD | `http.server.requests{outcome=SUCCESS, status=200, uri=/v2/activities/{activity-id}}` |

A heap dump of the affected PROD pod named the series: 53 timers in the family, 52 with a
`PrometheusHistogram`, one with `PrometheusTimer.histogram == null` and
`AbstractTimer.histogram == NoopHistogram`. Of all 458 timers in that heap, this was the only family
with a mixed shape.

The production case is **not** fully explained by this reproducer. The Micrometer warning above does
not appear in the production log at all, so there the filter was not installed late. The reproducer
shows what a meter with an empty distribution configuration does to the scrape, and that the registry
never repairs it. How that one meter ended up unconfigured in production is still open.
