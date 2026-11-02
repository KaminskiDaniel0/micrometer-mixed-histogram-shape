# Bug report draft — micrometer-metrics/micrometer

**Title:** `MicrometerCollector` merges two meter shapes under one name into an invalid
`MetricSnapshot`, which breaks the whole Prometheus scrape

## Describe the bug

When one meter of a family publishes a histogram and another does not, `MicrometerCollector.collect()`
merges their data points into a single `MetricSnapshot` whose declared type does not match its
contents. The Prometheus exposition writer then fails on a cast:

```
java.lang.ClassCastException: class io.prometheus.metrics.model.snapshots.SummarySnapshot$SummaryDataPointSnapshot
  cannot be cast to class io.prometheus.metrics.model.snapshots.HistogramSnapshot$HistogramDataPointSnapshot
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeClassicHistogramBuckets(...)
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeHistogram(...)
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.write(...)
```

The scrape endpoint answers HTTP 500 and returns **no families at all**, not even the healthy ones. The
process keeps serving traffic and reports itself healthy, so nothing alerts; it simply disappears from
the dashboards until it restarts.

## Where it happens

`io.micrometer.prometheusmetrics.MicrometerCollector#collect`:

```java
Map<String, Family> families = new HashMap<>();      // raw type

for (Child child : children.values()) {
    child.samples(conventionName)
        .forEach(family -> families.compute(family.getConventionName(),
            (name, matchingFamily) -> matchingFamily != null
                ? matchingFamily.addSamples(family.dataPointSnapshots) : family));
}
```

Three things combine:

1. The key is the convention name alone. The shape of the data points is not part of it.
2. `Map<String, Family>` is a **raw type**, so the type parameter of `Family<T extends DataPointSnapshot>`
   is erased at the one place where it would have mattered. `addSamples` accepts
   `SummaryDataPointSnapshot` into a list meant for `HistogramDataPointSnapshot` without a check, and
   the compiler says nothing.
3. The `metricSnapshotFactory` of whichever `Family` the loop meets first wraps the mixed list.

The two shapes come from `PrometheusMeterRegistry#addDistributionStatisticSamples`, which picks
`SummarySnapshot` when `histogramCounts.length == 0` and `HistogramSnapshot` otherwise — both under the
same convention name.

## Environment

* Micrometer version: 1.17.2 (newest patch of the 1.17 line; 1.17.0 behaves identically)
* Micrometer registry: prometheus, with `prometheus-metrics-exposition-textformats` 1.5.1
* OS: Linux
* Java version: 21
* Spring Boot 4.1.0, used only for `PropertiesMeterFilter` in the second finding below. The merge
  itself needs no Spring.

## To Reproduce

Minimal project: https://github.com/KaminskiDaniel0/micrometer-mixed-histogram-shape — `mvn test`.

`MergedFamilyTest` isolates this issue. It registers two timers under `http.server.requests`, one with
service level objectives set in code and one without, and asserts the `ClassCastException`. No
`MeterFilter` is involved.

Note that the direction of the cast is **arbitrary**. Both tests register the shapeless timer first and
the bucketed one second, yet they fail in opposite directions, because the winning factory follows the
iteration order of `MicrometerCollector.children`, a `ConcurrentHashMap` keyed by `Meter.Id`. Out of 40
tag pairs tried, 30 produced one direction and 10 the other. So neither the reported snapshot type nor
the writer method in the stack trace identifies what is wrong.

## Expected behavior

A registry should not build a `MetricSnapshot` that contradicts its own type. Any of these would be an
improvement over the current failure:

* Group the families by name **and** shape, and let the mismatch surface as a named, skipped family.
* Keep the generic type on the map so the merge cannot compile away its own safety.
* Fail fast at registration, naming the meter whose shape disagrees with its family, instead of at
  scrape time naming only two snapshot classes.

The practical point is diagnosis. Today the exception names the cast, never the metric. On a running
instance, narrowing it down takes a per-family probe with `?includedNames=` to find the family, and
then a heap dump to find the member — for a failure the registry could have named at registration.

## A second, related finding

A `MeterFilter` configured after a meter has been registered leaves that meter permanently
unconfigured, which is one way to arrive at the mixed family above.

`MeterRegistry.Config#meterFilter` adds the existing pre-filter ids to `stalePreFilterIds`. On the next
registration of such an id, `getOrCreateMeter` maps the id again, finds the existing meter under the
mapped id, calls `unmarkStaleId` and returns **that same meter**. The distribution configuration is
never recomputed, because the filter loop only runs in the branch taken when no meter exists yet.

`MixedHistogramShapeTest#aMeterRegisteredBeforeTheFilterKeepsItsEmptyShapeForever` asserts this through
object identity. For a Prometheus registry the existing warning is therefore not advisory — it predicts
a scrape that stays broken for the lifetime of the process. Issue #4920 asks for such use cases, hence
this note.

`MixedHistogramShapeTest#concurrentRegistrationIsSafe` is the negative control: 32 threads registering
25 ids of one family concurrently lose nothing, as expected, since the filter chain runs inside
`synchronized (meterMapLock)` and `filters` is `volatile`.
