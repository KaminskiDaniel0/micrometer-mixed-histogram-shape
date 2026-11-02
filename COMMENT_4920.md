# Comment draft — micrometer-metrics/micrometer, issue #4920

The warning added with this issue points here for use cases, so here is one. For a Prometheus registry
the warning is not advisory: it predicts a scrape that stays broken for the lifetime of the process.

**What happens**

A meter registered before a `MeterFilter` is installed receives an empty `DistributionStatisticConfig`
and therefore publishes no histogram. Installing the filter afterwards does not repair it:

* `MeterRegistry.Config#meterFilter` adds every existing pre-filter id to `stalePreFilterIds`.
* On the next registration of such an id, `getOrCreateMeter` maps the id again, finds the existing
  meter under the mapped id, calls `unmarkStaleId` and returns **that same meter**.
* The distribution configuration is never recomputed, because the filter loop sits in the branch that
  only runs when no meter exists yet.

So the meter keeps the shape it was born with, while every sibling registered after the filter gets
buckets. The family then carries two shapes under one name, and the scrape fails with a
`ClassCastException` for as long as the process lives. Only a restart clears it.

**Reproducer**

https://github.com/KaminskiDaniel0/micrometer-mixed-histogram-shape — `mvn test`, Micrometer 1.17.2,
prometheus registry, Java 21.

`MixedHistogramShapeTest#aMeterRegisteredBeforeTheFilterKeepsItsEmptyShapeForever` asserts the identity
directly: the `Timer` returned after the filter was installed is the same object as the one created
before it, it still reports no histogram counts, and both exposition formats then throw.

`MixedHistogramShapeTest#concurrentRegistrationIsSafe` is a negative control, included so that a
concurrency explanation can be ruled out rather than assumed. 32 threads registering 25 ids of one
family at once lose nothing, as expected, since the filter chain runs inside
`synchronized (meterMapLock)` and `filters` is `volatile`.

**What would help**

Rebuilding the meter when a late filter marks its id stale, instead of unmarking the id and returning
the meter the filter never configured. Failing that, the warning could say what it costs on a registry
whose exposition format is shape-sensitive.

The resulting invalid snapshot is a separate matter, reported on its own: see
`MicrometerCollector.collect()` merging two shapes under one name.
