# Issue draft — micrometer-metrics/micrometer, template "Bug report"

**Title:** `MicrometerCollector` merges two meter shapes under one name into an invalid
`MetricSnapshot`, breaking the whole Prometheus scrape

---

**Describe the bug**

When one meter of a family publishes a histogram and another does not, `MicrometerCollector.collect()`
merges their data points into a single `MetricSnapshot` whose declared type does not match its
contents. The exposition writer then fails on a cast:

```
java.lang.ClassCastException: class io.prometheus.metrics.model.snapshots.SummarySnapshot$SummaryDataPointSnapshot
  cannot be cast to class io.prometheus.metrics.model.snapshots.HistogramSnapshot$HistogramDataPointSnapshot
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeClassicHistogramBuckets(...)
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.writeHistogram(...)
    at io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter.write(...)
```

The scrape endpoint answers HTTP 500 and returns **no families at all**, not even the healthy ones. The
process keeps serving traffic and reports itself healthy, so nothing alerts. It simply disappears from
the dashboards until it restarts.

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
2. `Map<String, Family>` is a **raw type**, so the type parameter of
   `Family<T extends DataPointSnapshot>` is erased at the one place where it would have mattered.
   `addSamples` accepts `SummaryDataPointSnapshot` into a list meant for `HistogramDataPointSnapshot`
   without a check, and the compiler says nothing.
3. The `metricSnapshotFactory` of whichever `Family` the loop meets first wraps the mixed list.

The two shapes come from `PrometheusMeterRegistry#addDistributionStatisticSamples`, which builds a
`SummarySnapshot` family when `histogramCounts.length == 0` and a `HistogramSnapshot` family
otherwise — both under the same convention name.

**Environment**

* Micrometer version: 1.17.2 (newest patch of the 1.17 line; 1.17.0 behaves identically)
* Micrometer registry: prometheus, with `prometheus-metrics-exposition-textformats` 1.5.1
* OS: Linux
* Java version: 21

Spring Boot 4.1.0 is on the classpath of the sample project but takes no part in this bug. The merge
needs no Spring and no `MeterFilter`.

**To Reproduce**

Minimal project: https://github.com/KaminskiDaniel0/micrometer-mixed-histogram-shape — `mvn test`.

`MergedFamilyTest` isolates this issue. It registers two timers under `http.server.requests`, one with
service level objectives set in code and one without, and asserts the `ClassCastException`.

The direction of the cast is **arbitrary**. Both tests register the shapeless timer first and the
bucketed one second, yet they fail in opposite directions, because the winning factory follows the
iteration order of `MicrometerCollector.children` — a `ConcurrentHashMap` keyed by `Meter.Id`, so it
depends on the tag values. Out of 40 tag pairs tried, 30 produced one direction and 10 the other.
Neither the reported snapshot type nor the writer method in the stack trace identifies what is wrong.

**Expected behavior**

A registry should not build a `MetricSnapshot` that contradicts its own type. Any of these would be an
improvement over the current failure:

* Group the families by name **and** shape, and let the mismatch surface as a named, skipped family.
* Keep the generic type on the map so the merge cannot compile away its own safety.
* Fail fast at registration, naming the meter whose shape disagrees with its family, instead of at
  scrape time naming only two snapshot classes.

The practical point is diagnosis. Today the exception names the cast, never the metric. On a running
instance, narrowing it down takes a per-family probe with `?includedNames=` to find the family, and
then a heap dump to find the member — for a mismatch the registry could have named at registration.

**Additional context**

Two earlier reports describe the same symptom and were closed:

* #5150 — Prometheus ClassCastException while scraping (Micrometer 1.13 / SB 3.3)
* #5194 — ClassCastException `HistogramDataPointSnapshot` vs `SummaryDataPointSnapshot` when scraping
  two `PrometheusTimer`s, one with `publishPercentiles`, the other without

Both were read as a configuration mistake on the reporting side. This report is not a request to
re-open them. It points at the code that turns such a mistake into an invalid snapshot and a total
loss of the scrape, rather than into a message that names the offending meter.
