/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.metrics.instrument.prometheus;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.SimpleCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.metrics.instrument.*;
import org.springframework.metrics.instrument.Timer;
import org.springframework.metrics.instrument.internal.AbstractMeterRegistry;
import org.springframework.metrics.instrument.internal.MeterId;
import org.springframework.metrics.instrument.stats.hist.Histogram;
import org.springframework.metrics.instrument.stats.quantile.Quantiles;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.springframework.metrics.instrument.internal.MapAccess.computeIfAbsent;

/**
 * @author Jon Schneider
 */
public class PrometheusMeterRegistry extends AbstractMeterRegistry {
    private final CollectorRegistry registry;

    private final ConcurrentMap<String, Collector> collectorMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<MeterId, Meter> meterMap = new ConcurrentHashMap<>();

    public PrometheusMeterRegistry() {
        this(CollectorRegistry.defaultRegistry);
    }

    public PrometheusMeterRegistry(CollectorRegistry registry) {
        this(registry, Clock.SYSTEM);
    }

    @Autowired
    public PrometheusMeterRegistry(CollectorRegistry registry, Clock clock) {
        super(clock);
        this.registry = registry;
    }

    @Override
    public Collection<Meter> getMeters() {
        return meterMap.values();
    }

    @Override
    public <M extends Meter> Optional<M> findMeter(Class<M> mClass, String name, Iterable<Tag> tags) {
        Collection<Tag> tagsToMatch = new ArrayList<>();
        tags.forEach(tagsToMatch::add);

        //noinspection unchecked
        return meterMap.keySet().stream()
                .filter(id -> id.getName().equals(name))
                .filter(id -> Arrays.asList(id.getTags()).containsAll(tagsToMatch))
                .findAny()
                .map(meterMap::get)
                .map(m -> (M) m);
    }

    @Override
    public Counter counter(String name, Iterable<Tag> tags) {
        MeterId id = new MeterId(name, tags);
        io.prometheus.client.Counter counter = computeIfAbsent(collectorMap, name,
                n -> buildCollector(id, io.prometheus.client.Counter.build()));
        return computeIfAbsent(meterMap, id, c -> new PrometheusCounter(id, child(counter, id.getTags())));
    }

    @Override
    public DistributionSummary distributionSummary(String name, Iterable<Tag> tags, Quantiles quantiles, Histogram<?> histogram) {
        MeterId id = new MeterId(name, tags);
        final CustomPrometheusSummary summary = computeIfAbsent(collectorMap, name,
                n -> new CustomPrometheusSummary(name, stream(tags.spliterator(), false).map(Tag::getKey).collect(toList())).register(registry));
        return computeIfAbsent(meterMap, id, t -> new PrometheusDistributionSummary(id, summary.child(tags, quantiles, histogram)));
    }

    @Override
    protected Timer timer(String name, Iterable<Tag> tags, Quantiles quantiles, Histogram<?> histogram) {
        MeterId id = new MeterId(name, tags);
        final CustomPrometheusSummary summary = computeIfAbsent(collectorMap, name,
                n -> new CustomPrometheusSummary(name, stream(tags.spliterator(), false).map(Tag::getKey).collect(toList())).register(registry));
        return computeIfAbsent(meterMap, id, t -> new PrometheusTimer(id, summary.child(tags, quantiles, histogram), getClock()));
    }

    @Override
    public LongTaskTimer longTaskTimer(String name, Iterable<Tag> tags) {
        MeterId id = new MeterId(name, tags);
        final CustomPrometheusLongTaskTimer longTaskTimer = computeIfAbsent(collectorMap, name,
                n -> new CustomPrometheusLongTaskTimer(name, stream(tags.spliterator(), false).map(Tag::getKey).collect(toList()), getClock()).register(registry));
        return computeIfAbsent(meterMap, id, t -> new PrometheusLongTaskTimer(id, longTaskTimer.child(tags)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T gauge(String name, Iterable<Tag> tags, T obj, ToDoubleFunction<T> f) {
        final WeakReference<T> ref = new WeakReference<>(obj);

        MeterId id = new MeterId(name, tags);
        io.prometheus.client.Gauge gauge = computeIfAbsent(collectorMap, name,
                i -> buildCollector(id, io.prometheus.client.Gauge.build()));

        computeIfAbsent(meterMap, id, g -> {
            String[] labelValues = Arrays.stream(id.getTags())
                    .map(Tag::getValue)
                    .collect(Collectors.toList())
                    .toArray(new String[]{});

            Gauge.Child child = new Gauge.Child() {
                @Override
                public double get() {
                    final T obj = ref.get();
                    return (obj == null) ? Double.NaN : f.applyAsDouble(obj);
                }
            };

            gauge.setChild(child, labelValues);
            return new PrometheusGauge(id, child);
        });

        return obj;
    }

    @Override
    public MeterRegistry register(Meter meter) {
        Collector collector = new Collector() {
            @Override
            public List<MetricFamilySamples> collect() {
                List<MetricFamilySamples.Sample> samples = stream(meter.measure().spliterator(), false)
                        .map(m -> {
                            List<String> tagKeys = new ArrayList<>(m.getTags().length);
                            List<String> tagValues = new ArrayList<>(m.getTags().length);
                            for (Tag tag : m.getTags()) {
                                tagKeys.add(tag.getKey());
                                tagValues.add(tag.getValue());
                            }
                            return new MetricFamilySamples.Sample(m.getName(), tagKeys, tagValues, m.getValue());
                        })
                        .collect(toList());

                return Collections.singletonList(new MetricFamilySamples(meter.getName(), Type.UNTYPED, " ", samples));
            }
        };
        registry.register(collector);
        collectorMap.put(meter.getName(), collector);
        meterMap.put(new MeterId(meter.getName(), meter.getTags()), meter);
        return this;
    }

    /**
     * @return The underlying Prometheus {@link CollectorRegistry}.
     */
    public CollectorRegistry getPrometheusRegistry() {
        return registry;
    }

    private <B extends SimpleCollector.Builder<B, C>, C extends SimpleCollector<D>, D> C buildCollector(MeterId id,
                                                                                                        SimpleCollector.Builder<B, C> builder) {
        return builder
                .name(id.getName())
                .help(" ")
                .labelNames(Arrays.stream(id.getTags())
                        .map(Tag::getKey)
                        .collect(Collectors.toList())
                        .toArray(new String[]{}))
                .register(registry);
    }

    private <C extends SimpleCollector<D>, D> D child(C collector, Tag[] tags) {
        return collector.labels(Arrays.stream(tags)
                .map(Tag::getValue)
                .collect(Collectors.toList())
                .toArray(new String[]{}));
    }
}
