# micrometer-mixed-histogram-shape

What one meter of a family without a histogram does to a Prometheus scrape while its siblings carry
buckets.

On 1.17.2 the scrape answered HTTP 500 with a `ClassCastException` and delivered no family at all, not
even the healthy ones. On 1.18.0-RC1 the scrape answers 200 and delivers the shape that registered
first, while every meter of the other shape is refused and silently absent.

## Versions

**This branch measures Micrometer 1.18.0-RC1**, as asked for in issue #8141. Spring Boot 4.1.0,
`prometheus-metrics-*` 1.10.0 (1.18.0-RC1 needs it; Boot 4.1.0 still pins 1.5.1), Java 21. The `main`
branch measures 1.17.2, the newest patch of the 1.17 line; 1.17.0 behaves identically there.

## What changed on 1.18.0-RC1

The scrape no longer breaks. `PrometheusMeterRegistry` rejects the conflicting meter at registration
time and the endpoint answers 200. Three things are worth stating precisely, because they decide what
an operator sees.

1. **The first shape of a family wins, and the rest are shut out.** In the late-filter scenario the
   unconfigured meter registers first, so all ten correctly configured timers are refused. The scrape
   carries one series instead of eleven. On 1.17.2 the same setup delivered no series at all, so this
   is an improvement, but the loss is not limited to the odd meter.
2. **A refused meter stays alive and invisible.** It remains in `registry.getMeters()`, it accepts
   recordings, and re-registering its id returns the very same instance. Nothing on the application
   side can tell that it never reaches Prometheus.
3. **The rejection is announced once per JVM.** The first refused meter is named at WARN; every later
   one is logged at DEBUG, across families and across registries. A service that trips this on two
   families learns the name of one meter and nothing about the other.

## Run it

```bash
mvn test
```

All seven tests pass. Each test class runs in its own JVM, because the rejection WARN fires once per
JVM and the class that runs first would otherwise consume it.

## What the tests show

The order below is the order to read them in. `MixedHistogramShapeTest` carries the scenario: valid
configuration, one meter that misses the filter, and what the scrape then delivers. `MergedFamilyTest`
and `ConflictWarningTest` come after it, because they write the invalid state out by hand to isolate
the registry's own behaviour from the question of how that state arises.

### 1. `baselineScrapesCleanly`

The healthy case. `PropertiesMeterFilter` is installed before any meter exists, so every timer of
`http.server.requests` receives the service level objectives from
`management.metrics.distribution.slo[http.server.requests]`. Every series carries the same 17 buckets
and the scrape succeeds.

### 2. `aMeterRegisteredBeforeTheFilterTakesTheWholeFamilyDownWithIt`

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
same object as the one created before it, and it still reports no histogram counts.

On 1.18.0-RC1 the consequence shifts. The unconfigured meter registered first, so it owns the family,
and the ten timers that the filter configured correctly are all refused. The scrape carries one series
where eleven meters exist, and it carries no buckets at all. The test measures exactly that.

So the warning that Micrometer logs here is not advisory. For a Prometheus registry it predicts a
family reduced to its first member:

```
A MeterFilter is being configured after a Meter has been registered to this registry. ...
let the Micrometer maintainers know at https://github.com/micrometer-metrics/micrometer/issues/4920.
```

### 3. `concurrentRegistrationIsSafe`

A negative control. 32 threads register 25 ids of the same family at once, with the filter installed
from the start. Nothing is lost. This is expected, because `getOrCreateMeter` applies the filters
inside `synchronized (meterMapLock)` and `MeterRegistry.filters` is `volatile`. The test exists so
that a concurrency explanation can be ruled out rather than assumed.

### 4. `MergedFamilyTest` — the isolation test

Two timers under one name, one with service level objectives set in code and one without. **This code is
invalid by construction, and deliberately so.** It writes the mixed family out by hand, with no
`MeterFilter` anywhere, so that it measures one thing only: what the registry does with such a family.
How an application arrives at that state is the question of tests 1 to 3 above. Do not read this class
as the scenario.

On 1.17.2 `MicrometerCollector.collect()` merged both into one snapshot that contradicted its own type,
and the cast direction was arbitrary. On 1.18.0-RC1 the merge is gone: the second shape is refused at
registration, the scrape succeeds, and the three tests here pin what survives. The first registered
shape wins the family, order decides and not the shape, and the refused meter keeps recording while
never appearing in the exposition.

### 5. `ConflictWarningTest`

Two fresh registries, each with one conflict. The first rejection is named at WARN, the second is
logged at DEBUG. The suppression is per JVM, not per registry and not per family.

## Why this still matters on 1.18.0-RC1

The 500 is gone, and that is the larger half of the problem solved. What remains is that the surviving
shape is picked by registration order rather than by what the configuration asked for. When the odd
meter happens to register first, the family loses every correctly configured member instead of the one
odd one, and an operator reading the scrape sees a plausible metric with most of its series missing.

Two directions would still help:

* Decide the family by the configured shape rather than by which meter arrived first, or refuse the
  minority rather than the majority.
* Rebuild the meter when a late filter marks its id stale, instead of unmarking the id and returning
  the meter that the filter never configured. That removes the cause instead of handling it.

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

* `BUG_REPORT.md` — filed as micrometer issue #8141: `MicrometerCollector.collect()`
  merges two shapes under one name into an invalid `MetricSnapshot`. Covered by `MergedFamilyTest`.
* `COMMENT_8141_RC1.md` — the reply on issue #8141 after measuring 1.18.0-RC1: what the new
  registration-time rejection fixes, and the three things it leaves to the operator.
* `COMMENT_4920.md` — a short note for micrometer issue #4920: on a Prometheus registry a late
  `MeterFilter` is fatal rather than imprecise. The behaviour behind it is covered by
  `MixedHistogramShapeTest`.
