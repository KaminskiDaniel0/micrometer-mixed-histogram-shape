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

Spring Boot 4.1.0, Micrometer 1.17.2, `prometheus-metrics-exposition-textformats` 1.5.1, Java 21.
1.17.2 is the newest patch of the 1.17 line; 1.17.0 behaves identically. The merge in
`MicrometerCollector` is not new to these versions, but only these were measured.

## Run it

```bash
mvn test
```

All five tests pass. The one that reproduces the defect asserts that the scrape throws.

## What the tests show

`MergedFamilyTest` covers the defect in `MicrometerCollector.collect()`. `MixedHistogramShapeTest`
covers how a meter arrives at the mixed shape and whether the registry ever repairs it.

### 0. `MergedFamilyTest`

Two timers under one name, one with service level objectives set in code and one without. No
`MeterFilter` takes part, so this isolates the merge from the configuration question.

`MicrometerCollector.collect()` groups data points into `Map<String, Family> families`, keyed by the
convention name alone. The map is a **raw type**, so `matchingFamily.addSamples(...)` adds data points
of one shape to a list meant for the other with no check. The `metricSnapshotFactory` of whichever
family the loop meets first wraps the mixed list.

The direction of the cast is arbitrary. Both tests register the shapeless timer first, yet they fail in
opposite directions, because the winning factory follows the iteration order of
`MicrometerCollector.children` — a `ConcurrentHashMap` keyed by `Meter.Id`, so it depends on the tag
values. Out of 40 tag pairs tried, 30 gave one direction and 10 the other. Neither the reported
snapshot type nor the writer method in the stack trace identifies the real problem.

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

## How this looks on a running service

The failure has been seen on long-running deployments of a Spring Boot service, on more than one
environment. Two observations from those sightings are worth repeating, because they shape what a fix
would have to achieve.

**The affected series looks arbitrary.** Different deployments lost a different member of the same
family, on different endpoints, methods and status codes. Nothing about the endpoint predicts it.

**A heap dump is the only thing that names it.** On an affected instance the family held 53 timers.
52 carried a `PrometheusHistogram`; one had `PrometheusTimer.histogram == null` and
`AbstractTimer.histogram == NoopHistogram`. Of 458 timers in that heap, this was the only family with
a mixed shape. Reading the `AbstractTimer` field alone is misleading, because a timer that publishes
percentiles rather than buckets also carries a non-histogram there; the Prometheus-side field is the
one that decides.

**The reproducer does not explain every sighting.** In the scenario of test 2 Micrometer logs its
late-filter warning. On the instances observed that warning was absent, so a late filter was not what
happened there. What this project shows is what a meter with an empty distribution configuration does
to the scrape, and that the registry never repairs it. How such a meter can arise while the filter
chain is fully in place is still open.

## Upstream reports

Two drafts, deliberately separate, because they are two different defects:

* `BUG_REPORT.md` — a new bug report for `micrometer-metrics/micrometer`: `MicrometerCollector.collect()`
  merges two shapes under one name into an invalid `MetricSnapshot`. Covered by `MergedFamilyTest`.
* `COMMENT_4920.md` — a comment for micrometer issue #4920, whose warning asks for use cases: a late
  `MeterFilter` leaves a meter permanently unconfigured. Covered by `MixedHistogramShapeTest`.
