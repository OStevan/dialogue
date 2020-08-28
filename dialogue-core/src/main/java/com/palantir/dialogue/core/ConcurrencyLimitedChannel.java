/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.dialogue.core;

import com.codahale.metrics.Meter;
import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Response;
import com.palantir.dialogue.core.CautiousIncreaseAggressiveDecreaseConcurrencyLimiter.Behavior;
import com.palantir.dialogue.futures.DialogueFutures;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.util.Optional;
import java.util.stream.LongStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A channel that monitors the successes and failures of requests in order to determine the number of concurrent
 * requests allowed to a particular channel. If the channel's concurrency limit has been reached, the
 * {@link #maybeExecute} method returns empty.
 */
final class ConcurrencyLimitedChannel implements LimitedChannel {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitedChannel.class);

    private final Meter limitedMeter;
    private final NeverThrowChannel delegate;
    private final CautiousIncreaseAggressiveDecreaseConcurrencyLimiter limiter;

    static LimitedChannel create(Config cf, Channel channel, int uriIndex, Behavior behavior) {
        ClientConfiguration.ClientQoS clientQoS = cf.clientConf().clientQoS();
        switch (clientQoS) {
            case ENABLED:
                TaggedMetricRegistry metrics = cf.clientConf().taggedMetricRegistry();
                return new ConcurrencyLimitedChannel(
                        channel, createLimiter(behavior), cf.channelName(), uriIndex, metrics);
            case DANGEROUS_DISABLE_SYMPATHETIC_CLIENT_QOS:
                return new ChannelToLimitedChannelAdapter(channel);
        }
        throw new SafeIllegalStateException(
                "Encountered unknown client QoS configuration", SafeArg.of("ClientQoS", clientQoS));
    }

    ConcurrencyLimitedChannel(
            Channel delegate,
            CautiousIncreaseAggressiveDecreaseConcurrencyLimiter limiter,
            String channelName,
            int uriIndex,
            TaggedMetricRegistry taggedMetrics) {
        this.delegate = new NeverThrowChannel(delegate);
        this.limitedMeter = DialogueClientMetrics.of(taggedMetrics)
                .limited()
                .channelName(channelName)
                .reason(getClass().getSimpleName())
                .build();
        this.limiter = limiter;
        Preconditions.checkArgument(
                uriIndex != -1, "uriIndex must be specified", SafeArg.of("channel-name", channelName));
        DialogueConcurrencylimiterMetrics metrics = DialogueConcurrencylimiterMetrics.of(taggedMetrics);
        DialogueInternalWeakReducingGauge.getOrCreateDouble(
                taggedMetrics,
                metrics.max()
                        .channelName(channelName)
                        .hostIndex(Integer.toString(uriIndex))
                        .buildMetricName(),
                ConcurrencyLimitedChannel::getMax,
                doubleStream -> doubleStream.min().orElse(0D),
                this);
        DialogueInternalWeakReducingGauge.getOrCreate(
                taggedMetrics,
                metrics.inFlight()
                        .channelName(channelName)
                        .hostIndex(Integer.toString(uriIndex))
                        .buildMetricName(),
                concurrencyLimitedChannel -> concurrencyLimitedChannel.limiter.getInflight(),
                LongStream::sum,
                this);
    }

    static CautiousIncreaseAggressiveDecreaseConcurrencyLimiter createLimiter(Behavior behavior) {
        return new CautiousIncreaseAggressiveDecreaseConcurrencyLimiter(behavior);
    }

    @Override
    public Optional<ListenableFuture<Response>> maybeExecute(Endpoint endpoint, Request request) {
        Optional<CautiousIncreaseAggressiveDecreaseConcurrencyLimiter.Permit> maybePermit = limiter.acquire();
        if (maybePermit.isPresent()) {
            CautiousIncreaseAggressiveDecreaseConcurrencyLimiter.Permit permit = maybePermit.get();
            logPermitAcquired();
            ListenableFuture<Response> result = delegate.execute(endpoint, request);
            DialogueFutures.addDirectCallback(result, permit);
            return Optional.of(result);
        } else {
            logPermitRefused();
            limitedMeter.mark();
            return Optional.empty();
        }
    }

    private void logPermitAcquired() {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Sending {}/{}",
                    SafeArg.of("inflight", limiter.getInflight()),
                    SafeArg.of("max", limiter.getLimit()));
        }
    }

    private void logPermitRefused() {
        if (log.isDebugEnabled()) {
            log.debug("Limited {}", SafeArg.of("max", limiter.getLimit()));
        }
    }

    @Override
    public String toString() {
        return "ConcurrencyLimitedChannel{" + delegate + '}';
    }

    private double getMax() {
        return limiter.getLimit();
    }
}
