# Comment draft — micrometer-metrics/micrometer, issue #4920

Not a use case for late filters — we do not want them. Just a counterexample to "the negative effects
of doing this can be minimal": on a Prometheus registry a late filter is not imprecise, it is fatal.
The meter keeps its empty `DistributionStatisticConfig` for the life of the process, its family ends up
carrying two shapes, and the entire scrape then fails with a `ClassCastException` — silently, because
the process stays healthy and keeps serving traffic. Mechanism and reproducer in #8141.
