Thanks for the pointer — I measured against `1.18.0-RC1`. The reproducer now has a branch for it, with
the 1.17.2 state left on `main` for comparison:

https://github.com/KaminskiDaniel0/micrometer-mixed-histogram-shape/tree/test/micrometer-1.18.0-RC1

To be explicit up front: I am not asking for two types under one family name. I agree that the type
belongs to the metric family and that one of the two shapes has to lose. The three points below are all
compatible with that rule — they are about *which* shape loses, what happens to the loser, and whether
an operator can find out.

**The scrape failure is gone.** No `ClassCastException`, no HTTP 500, both exposition formats render.
The conflicting meter is refused at registration and the warning names the meter id and both types.
That is the larger half of this issue solved, thank you.

One note on setting it up: `1.18.0-RC1` needs `io.prometheus:prometheus-metrics-*` `1.10.0`. Spring
Boot `4.1.0` still pins `1.5.1`, and with that combination every meter registration fails with
`NoClassDefFoundError: io/prometheus/metrics/model/snapshots/MetricFamilyDescriptor`. Overriding
`prometheus-client.version` fixes it.

Three things I measured on top, because they decide what an operator ends up seeing. All three are
pinned by tests on that branch.

**1. The first registered shape wins the family, so the loss can hit the majority.** In the realistic
scenario — one meter registers before the `MeterFilter` is in place, the other ten after it — the
unconfigured meter is the one that arrives first. It owns the family, and all ten correctly configured
timers are refused. The scrape then carries 1 series where 11 meters exist, and no buckets at all. On
1.17.2 the same setup delivered 0 series, so this is still an improvement. But the survivor is picked
by arrival order rather than by what the configuration asked for. On the production instance behind
this report the ratio was 52 bucketed timers to 1 unconfigured one. Letting the majority shape win, or
the configured shape, would turn a family-wide loss into a single-series loss without ever putting two
types under one name.

**2. A refused meter stays alive and invisible.** It remains in `registry.getMeters()`, it accepts
recordings, and re-registering the same id returns that very same instance. Nothing on the application
side can tell that it never reaches Prometheus.

**3. The rejection is announced once per JVM.** The first refused meter is logged at WARN; every later
one goes to DEBUG — across metric families and across registries, not just within one family. A service
that trips this on two families learns one meter name and nothing about the other. Since that message
is the only thing that names the offending meter, suppressing per family rather than per JVM would cost
little.

On the "make them the same type or use different names" advice: the second option is not available
here. `http.server.requests` is the name Spring gives every request; we do not choose it. The first
option is what our configuration already does — a single
`management.metrics.distribution.slo[http.server.requests]` entry for the whole family, which is valid.
The mixed state still arises, because one meter is created before the filter chain is applied. On the
production instances where we first saw this, the late-filter warning was absent, so even that path
does not explain every sighting. A heap dump was the only way to name the meter there: 52 of 53 timers
in the family carried a `PrometheusHistogram`, one had `PrometheusTimer.histogram == null`.

So I am not asking for #5150 or #5194 to be reopened. I am asking that an invalid family reachable from
valid configuration cost the odd meter rather than the family, and that it stay findable afterwards.

Happy to test anything else against the branch.
