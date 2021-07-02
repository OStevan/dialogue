/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Response;
import com.palantir.dialogue.futures.DialogueFutures;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.tracing.CloseableSpan;
import com.palantir.tracing.DetachedSpan;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Value.Enclosing
final class StickyQueueChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(StickyQueueChannel.class);

    private final Deque<StickyQueueChannel.DeferredCall> queuedCalls;
    private final NeverThrowLimitedChannel delegate;
    private final String channelName;
    // Tracks requests that are current executing in delegate and are not tracked in queuedCalls
    private final AtomicInteger queueSizeEstimate = new AtomicInteger(0);
    private final int maxQueueSize;
    private final Supplier<Counter> queueSizeCounter;
    private final Timer queuedTime;
    private final Supplier<ListenableFuture<Response>> limitedResultSupplier;
    // Metrics aren't reported until the queue is first used, allowing per-endpoint queues to
    // avoid creating unnecessary data.
    private volatile boolean shouldRecordQueueMetrics;

    StickyQueueChannel(
            LimitedChannel delegate,
            String channelName,
            QueuedChannel.QueuedChannelInstrumentation metrics,
            int maxQueueSize) {
        this.delegate = new NeverThrowLimitedChannel(delegate);
        this.channelName = channelName;
        // Do _not_ call size on a ConcurrentLinkedDeque. Unlike other collections, size is an O(n) operation.
        this.queuedCalls = new StickyQueueChannel.ProtectedConcurrentLinkedDeque<>();
        this.maxQueueSize = maxQueueSize;
        // Lazily create the counter. Unlike meters, timers, and histograms, counters cannot be ignored when they have
        // zero interactions because they support both increment and decrement operations.
        this.queueSizeCounter = Suppliers.memoize(metrics::requestsQueued);
        this.queuedTime = metrics.requestQueuedTime();
        this.limitedResultSupplier = () -> Futures.immediateFailedFuture(new SafeRuntimeException(
                "Unable to make a request (queue is full)", SafeArg.of("maxQueueSize", maxQueueSize)));
    }

    static QueuedChannel create(Config cf, LimitedChannel delegate) {
        return new QueuedChannel(
                delegate,
                cf.channelName(),
                channelInstrumentation(
                        DialogueClientMetrics.of(cf.clientConf().taggedMetricRegistry()), cf.channelName()),
                cf.maxQueueSize());
    }

    static QueuedChannel create(Config cf, Endpoint endpoint, LimitedChannel delegate) {
        return new QueuedChannel(
                delegate,
                cf.channelName(),
                endpointInstrumentation(
                        DialogueClientMetrics.of(cf.clientConf().taggedMetricRegistry()),
                        cf.channelName(),
                        endpoint.serviceName(),
                        endpoint.endpointName()),
                cf.maxQueueSize());
    }

    @Override
    public ListenableFuture<Response> execute(Endpoint endpoint, Request request) {
        return maybeExecute(endpoint, request).orElseGet(limitedResultSupplier);
    }

    /**
     * Enqueues and tries to schedule as many queued tasks as possible.
     */
    @VisibleForTesting
    Optional<ListenableFuture<Response>> maybeExecute(Endpoint endpoint, Request request) {
        // Optimistically avoid the queue in the fast path.
        // Queuing adds contention between threads and should be avoided unless we need to shed load.
        if (queueSizeEstimate.get() <= 0) {
            Optional<ListenableFuture<Response>> maybeResult = delegate.maybeExecute(endpoint, request);
            if (maybeResult.isPresent()) {
                ListenableFuture<Response> result = maybeResult.get();
                DialogueFutures.addDirectListener(result, this::onCompletion);
                // While the queue was avoid, this is equivalent to spending zero time on the queue.
                if (shouldRecordQueueMetrics) {
                    queuedTime.update(0, TimeUnit.NANOSECONDS);
                }
                return maybeResult;
            }
        }

        // Important to read the queue size here as well as prior to the optimistic maybeExecute because
        // maybeExecute may take sufficiently long that other requests could be queued.
        if (queueSizeEstimate.get() >= maxQueueSize) {
            return Optional.empty();
        }

        shouldRecordQueueMetrics = true;

        StickyQueueChannel.DeferredCall components = StickyQueueChannel.DeferredCall.builder()
                .endpoint(endpoint)
                .request(request)
                .response(SettableFuture.create())
                .span(DetachedSpan.start("Dialogue-request-enqueued"))
                .timer(queuedTime.time())
                .build();

        if (!queuedCalls.offer(components)) {
            // Should never happen, ConcurrentLinkedDeque has no maximum size
            return Optional.empty();
        }
        int newSize = incrementQueueSize();

        if (log.isDebugEnabled()) {
            log.debug(
                    "Request queued {} on channel {}",
                    SafeArg.of("queueSize", newSize),
                    SafeArg.of("channelName", channelName));
        }

        schedule();

        return Optional.of(components.response());
    }

    private void onCompletion() {
        schedule();
    }

    /**
     * Try to schedule as many tasks as possible. Called when requests are submitted and when they complete.
     */
    @VisibleForTesting
    void schedule() {
        int numScheduled = 0;
        while (scheduleNextTask()) {
            numScheduled++;
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "Scheduled {} requests on channel {}",
                    SafeArg.of("numScheduled", numScheduled),
                    SafeArg.of("channelName", channelName));
        }
    }

    private int incrementQueueSize() {
        queueSizeCounter.get().inc();
        return queueSizeEstimate.incrementAndGet();
    }

    private void decrementQueueSize() {
        queueSizeEstimate.decrementAndGet();
        queueSizeCounter.get().dec();
    }

    /**
     * Get the next call and attempt to execute it. If it is runnable, wire up the underlying future to the one
     * previously returned to the caller. If it is not runnable, add it back into the queue. Returns true if more
     * tasks may be able to be scheduled, and false otherwise.
     */
    private boolean scheduleNextTask() {
        StickyQueueChannel.DeferredCall queueHead = queuedCalls.poll();
        if (queueHead == null) {
            return false;
        }
        SettableFuture<Response> queuedResponse = queueHead.response();
        // If the future has been completed (most likely via cancel) the call should not be queued.
        // There's a race where cancel may be invoked between this check and execution, but the scheduled
        // request will be quickly cancelled in that case.
        if (queuedResponse.isDone()) {
            decrementQueueSize();
            queueHead.span().complete();
            queueHead.timer().stop();
            return true;
        }
        try (CloseableSpan ignored = queueHead.span().childSpan("Dialogue-request-scheduled")) {
            Endpoint endpoint = queueHead.endpoint();
            Optional<ListenableFuture<Response>> maybeResponse = delegate.maybeExecute(endpoint, queueHead.request());

            if (maybeResponse.isPresent()) {
                decrementQueueSize();
                ListenableFuture<Response> response = maybeResponse.get();
                queueHead.span().complete();
                queueHead.timer().stop();
                DialogueFutures.addDirectCallback(response, new StickyQueueChannel.ForwardAndSchedule(queuedResponse));
                DialogueFutures.addDirectListener(queuedResponse, () -> {
                    if (queuedResponse.isCancelled()) {
                        // TODO(ckozak): Consider capturing the argument value provided to cancel to propagate
                        // here.
                        // Currently cancel(false) will be converted to cancel(true)
                        if (!response.cancel(true) && log.isDebugEnabled()) {
                            log.debug(
                                    "Failed to cancel delegate response, it should be reported by ForwardAndSchedule "
                                            + "logging",
                                    SafeArg.of("channel", channelName),
                                    SafeArg.of("service", endpoint.serviceName()),
                                    SafeArg.of("endpoint", endpoint.endpointName()));
                        }
                    }
                });
                return true;
            } else {
                if (!queuedCalls.offerFirst(queueHead)) {
                    // Should never happen, ConcurrentLinkedDeque has no maximum size
                    log.error(
                            "Failed to add an attempted call back to the deque",
                            SafeArg.of("channel", channelName),
                            SafeArg.of("service", endpoint.serviceName()),
                            SafeArg.of("endpoint", endpoint.endpointName()));
                    decrementQueueSize();
                    queueHead.timer().stop();
                    if (!queuedResponse.setException(new SafeRuntimeException(
                            "Failed to req-queue request",
                            SafeArg.of("channel", channelName),
                            SafeArg.of("service", endpoint.serviceName()),
                            SafeArg.of("endpoint", endpoint.endpointName())))) {
                        log.debug(
                                "Queued response has already been completed",
                                SafeArg.of("channel", channelName),
                                SafeArg.of("service", endpoint.serviceName()),
                                SafeArg.of("endpoint", endpoint.endpointName()));
                    }
                }
                return false;
            }
        }
    }

    @Override
    public String toString() {
        return "QueuedChannel{queueSizeEstimate="
                + queueSizeEstimate + ", maxQueueSize="
                + maxQueueSize + ", delegate="
                + delegate + '}';
    }

    /**
     * Forward the success or failure of the call to the SettableFuture that was previously returned to the caller.
     * This also schedules the next set of requests to be run.
     */
    private class ForwardAndSchedule implements FutureCallback<Response> {
        private final SettableFuture<Response> response;

        ForwardAndSchedule(SettableFuture<Response> response) {
            this.response = response;
        }

        @Override
        public void onSuccess(Response result) {
            if (!response.set(result)) {
                result.close();
            }
            schedule();
        }

        @Override
        public void onFailure(Throwable throwable) {
            if (!response.setException(throwable)) {
                if (throwable instanceof CancellationException) {
                    log.debug("Call was canceled", throwable);
                } else {
                    log.info("Call failed after the future completed", throwable);
                }
            }
            schedule();
        }
    }

    @Value.Immutable
    interface DeferredCall {
        Endpoint endpoint();

        Request request();

        SettableFuture<Response> response();

        DetachedSpan span();

        Timer.Context timer();

        class Builder extends ImmutableStickyQueueChannel.DeferredCall.Builder {}

        static StickyQueueChannel.DeferredCall.Builder builder() {
            return new StickyQueueChannel.DeferredCall.Builder();
        }
    }

    private static final class ProtectedConcurrentLinkedDeque<T> extends ConcurrentLinkedDeque<T> {

        @Override
        public int size() {
            throw new UnsupportedOperationException("size should never be called on a ConcurrentLinkedDeque");
        }
    }

    interface QueuedChannelInstrumentation {
        Counter requestsQueued();

        Timer requestQueuedTime();
    }

    static QueuedChannel.QueuedChannelInstrumentation channelInstrumentation(
            DialogueClientMetrics metrics, String channelName) {
        return new QueuedChannel.QueuedChannelInstrumentation() {
            @Override
            public Counter requestsQueued() {
                return metrics.requestsQueued(channelName);
            }

            @Override
            public Timer requestQueuedTime() {
                return metrics.requestQueuedTime(channelName);
            }
        };
    }

    static QueuedChannel.QueuedChannelInstrumentation endpointInstrumentation(
            DialogueClientMetrics metrics, String channelName, String service, String endpoint) {
        return new QueuedChannel.QueuedChannelInstrumentation() {
            @Override
            public Counter requestsQueued() {
                return metrics.requestsEndpointQueued()
                        .channelName(channelName)
                        .serviceName(service)
                        .endpoint(endpoint)
                        .build();
            }

            @Override
            public Timer requestQueuedTime() {
                return metrics.requestEndpointQueuedTime()
                        .channelName(channelName)
                        .serviceName(service)
                        .endpoint(endpoint)
                        .build();
            }
        };
    }
}
