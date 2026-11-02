# Comment draft — micrometer-metrics/micrometer, issue #4920

Not a use case for configuring filters late — we do not want that. One data point on what it costs,
since the decision here is described as open.

A meter registered before a `MeterFilter` is installed receives an empty `DistributionStatisticConfig`,
and installing the filter afterwards never repairs it. `Config#meterFilter` adds the existing
pre-filter ids to `stalePreFilterIds`, but on the next registration of such an id `getOrCreateMeter`
finds the meter under the mapped id, calls `unmarkStaleId` and returns **that same meter**. The
distribution configuration is not recomputed, because the filter loop only runs in the branch taken
when no meter exists yet.

On a Prometheus registry that is fatal rather than imprecise: the family then carries two shapes, and
`MicrometerCollector.collect()` merges them into an invalid snapshot, so the whole scrape fails with a
`ClassCastException` for the lifetime of the process. Reported separately in #8141.

Reproducer, asserted through object identity:
https://github.com/KaminskiDaniel0/micrometer-mixed-histogram-shape
