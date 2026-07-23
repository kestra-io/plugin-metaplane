package io.kestra.plugin.metaplane;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Gate a pipeline on one or more Metaplane monitors, synchronously",
    description = """
        Optionally enqueues one or more monitors to run, then polls their status until each result is
        fresh (its timestamp is at or after the moment this task started) or a bounded timeout elapses.
        Unlike Get, this task can fail the flow itself: it combines every monitor's effective status
        (escalating a stale result to FAIL when maxAge is set) using failStrategy to decide whether the
        gate passes.

        When runFirst is false, no run is triggered: each monitor's current status is read once and,
        if maxAge is set, flagged stale when older than maxAge. When runFirst is true, a stale result
        can never occur since the polled result is always freshly produced after this task started.

        Throws an actionable error naming the still-pending monitor(s) if the poll times out before
        every monitor becomes fresh.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a Metaplane monitor and gate the pipeline synchronously on its fresh result",
            full = true,
            code = """
                id: metaplane_sync_gate
                namespace: company.team

                tasks:
                  - id: quality_gate
                    type: io.kestra.plugin.metaplane.Gate
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorIds:
                      - "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                    runFirst: true
                    pollInterval: PT10S
                    timeout: PT10M
                    failStrategy: FAIL_IF_ANY

                  - id: publish
                    type: io.kestra.plugin.core.log.Log
                    message: "Quality gate passed, publishing. Details: {{ outputs.quality_gate.monitors }}"
                """
        ),
        @Example(
            title = "Batch-gate several monitors with a staleness check, without triggering a new run",
            full = true,
            code = """
                id: metaplane_batch_gate
                namespace: company.team

                tasks:
                  - id: gate
                    type: io.kestra.plugin.metaplane.Gate
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorIds:
                      - "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                      - "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
                    runFirst: false
                    maxAge: PT6H
                    failStrategy: NONE
                    failOn:
                      - FAIL
                      - ERROR

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Gate passed={{ outputs.gate.passed }}, failed monitors={{ outputs.gate.failedMonitorIds }}"
                """
        )
    }
)
public class Gate extends AbstractMetaplaneTask implements RunnableTask<Gate.Output> {

    private static final Duration MAX_POLL_INTERVAL = Duration.ofHours(1);
    private static final Duration MAX_TIMEOUT = Duration.ofHours(24);
    private static final Duration MAX_MAX_AGE = Duration.ofHours(24);

    @Schema(
        title = "Monitor IDs to gate on",
        description = "UUIDs of the Metaplane monitors to poll and evaluate. Must contain at least one ID."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> monitorIds;

    @Schema(
        title = "Run the monitors before gating",
        description = "When true, enqueues every monitor to run (as Run does) before polling, and only accepts a " +
            "result whose timestamp is at or after this task's start. When false, no run is triggered: each " +
            "monitor's current status is read directly. Defaults to false."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Boolean> runFirst = Property.ofValue(false);

    @Schema(
        title = "Delay between two polls",
        description = "ISO-8601 duration between poll rounds while runFirst is true and at least one monitor has " +
            "not yet produced a fresh result. Must be strictly positive and at most PT1H (1 hour). Defaults to PT10S."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "Maximum time to wait for a fresh result",
        description = "ISO-8601 duration. The task fails with an actionable error naming the still-pending " +
            "monitor(s) if this deadline elapses before every monitor has produced a fresh result. Must be " +
            "strictly positive and at most PT24H (24 hours), since this task blocks the flow execution for its " +
            "whole duration. Note the actual wait may end up to one pollInterval short of this value, since the " +
            "loop only starts a new poll round if it can complete before the deadline. Defaults to PT10M."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(10));

    @Schema(
        title = "Maximum acceptable age of a monitor's result",
        description = "ISO-8601 duration. Only applied when runFirst is false: a monitor whose result is older " +
            "than this age is flagged stale and its effective status is escalated to FAIL for the purposes of " +
            "the gate, regardless of its reported status. Must be at most PT24H (24 hours). Optional, no " +
            "staleness check is performed by default."
    )
    @PluginProperty(group = "reliability")
    private Property<Duration> maxAge;

    @Schema(
        title = "Multi-monitor fail strategy",
        description = """
            How the effective status of every monitor is combined into a single pass/fail decision:
            - FAIL_FAST: stop polling as soon as one monitor's effective status is in failOn; monitors never
              reached are reported as pending, not fabricated.
            - FAIL_IF_ANY: the gate fails if at least one monitor's effective status is in failOn. Default.
            - FAIL_IF_ALL: the gate fails only if every monitor's effective status is in failOn.
            - NONE: the gate never fails; failedMonitorIds is still populated for reporting.
            """
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FailStrategy> failStrategy = Property.ofValue(FailStrategy.FAIL_IF_ANY);

    @Schema(
        title = "Statuses considered failing",
        description = "Monitor statuses whose presence counts as a failure for the fail strategy above. Defaults " +
            "to FAIL and ERROR."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<List<MonitorStatus>> failOn = Property.ofValue(List.of(MonitorStatus.FAIL, MonitorStatus.ERROR));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rMonitorIds = runContext.render(monitorIds).asList(String.class);
        if (rMonitorIds.isEmpty()) {
            throw new IllegalArgumentException("monitorIds must contain at least one monitor ID");
        }

        var rPollInterval = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(10));
        if (rPollInterval.isZero() || rPollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be strictly positive");
        }
        if (rPollInterval.compareTo(MAX_POLL_INTERVAL) > 0) {
            throw new IllegalArgumentException("pollInterval must not exceed " + MAX_POLL_INTERVAL);
        }

        var rTimeout = runContext.render(timeout).as(Duration.class).orElse(Duration.ofMinutes(10));
        if (rTimeout.isZero() || rTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        if (rTimeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must not exceed " + MAX_TIMEOUT);
        }

        var rMaxAge = runContext.render(maxAge).as(Duration.class).orElse(null);
        if (rMaxAge != null && rMaxAge.compareTo(MAX_MAX_AGE) > 0) {
            throw new IllegalArgumentException("maxAge must not exceed " + MAX_MAX_AGE);
        }
        var rRunFirst = runContext.render(runFirst).as(Boolean.class).orElse(false);
        var rFailStrategy = runContext.render(failStrategy).as(FailStrategy.class).orElse(FailStrategy.FAIL_IF_ANY);
        var rFailOn = runContext.render(failOn).asList(MonitorStatus.class);

        var rApiToken = renderApiToken(runContext);
        var rBaseUrl = renderBaseUrl(runContext);

        var start = Instant.now();

        if (rRunFirst) {
            logger.info("Enqueuing {} Metaplane monitor(s) to run before gating: {}", rMonitorIds.size(), rMonitorIds);
            enqueueMonitors(runContext, this.options, rApiToken, rBaseUrl, rMonitorIds);
        }

        var deadline = start.plus(rTimeout);
        var pending = new LinkedHashSet<>(rMonitorIds);
        var resolved = new LinkedHashMap<String, MonitorStatusResponse>();
        var failFastTriggered = false;

        outer:
        while (true) {
            for (var monitorId : List.copyOf(pending)) {
                var status = fetchMonitorStatus(runContext, this.options, rApiToken, rBaseUrl, monitorId);

                if (!rRunFirst || (status.getTimestamp() != null && !status.getTimestamp().isBefore(start))) {
                    resolved.put(monitorId, status);
                    pending.remove(monitorId);

                    if (rFailStrategy == FailStrategy.FAIL_FAST
                        && rFailOn.contains(effectiveStatus(status, rMaxAge, rRunFirst))) {
                        failFastTriggered = true;
                        break outer;
                    }
                }
            }

            if (pending.isEmpty()) {
                break;
            }

            var now = Instant.now();
            if (!now.plus(rPollInterval).isBefore(deadline)) {
                break;
            }

            try {
                Thread.sleep(rPollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a fresh result from Metaplane monitor(s) " + pending, e);
            }
        }

        if (!pending.isEmpty() && !failFastTriggered) {
            throw new IllegalStateException(
                "Timed out after " + rTimeout + " waiting for a fresh result from Metaplane monitor(s) " + pending +
                    ". Increase timeout, verify the monitor(s) actually run (e.g. set runFirst to true), or " +
                    "increase pollInterval if it is close to timeout."
            );
        }

        var monitorResults = new ArrayList<MonitorResult>();
        var failedMonitorIds = new ArrayList<String>();

        for (var monitorId : rMonitorIds) {
            var status = resolved.get(monitorId);

            if (status == null) {
                monitorResults.add(MonitorResult.builder().monitorId(monitorId).build());
                continue;
            }

            var stale = isStale(status, rMaxAge, rRunFirst);
            var effective = stale ? MonitorStatus.FAIL : status.overallStatus();

            monitorResults.add(MonitorResult.builder()
                .monitorId(monitorId)
                .status(status.overallStatus())
                .checkedAt(status.getTimestamp())
                .stale(stale)
                .series(status.getStatuses())
                .build());

            if (rFailOn.contains(effective)) {
                failedMonitorIds.add(monitorId);
            }
        }

        var passed = switch (rFailStrategy) {
            case NONE -> true;
            case FAIL_FAST, FAIL_IF_ANY -> failedMonitorIds.isEmpty();
            case FAIL_IF_ALL -> failedMonitorIds.size() < resolved.size();
        };

        logger.info("Metaplane gate {}: {} monitor(s) evaluated, {} failing", passed ? "PASSED" : "FAILED", resolved.size(), failedMonitorIds.size());

        return Output.builder()
            .passed(passed)
            .failedMonitorIds(failedMonitorIds)
            .monitors(monitorResults)
            .build();
    }

    /**
     * A stale result is only meaningful when runFirst is false: a runFirst=true result is always polled
     * until its timestamp advances past this task's start, so it is fresh by construction.
     */
    private static boolean isStale(MonitorStatusResponse status, Duration maxAge, boolean runFirst) {
        if (runFirst || maxAge == null) {
            return false;
        }
        return status.getTimestamp() == null || status.getTimestamp().isBefore(Instant.now().minus(maxAge));
    }

    private static MonitorStatus effectiveStatus(MonitorStatusResponse status, Duration maxAge, boolean runFirst) {
        return isStale(status, maxAge, runFirst) ? MonitorStatus.FAIL : status.overallStatus();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Whether the gate passed", description = "Always true when failStrategy is NONE.")
        private final boolean passed;

        @Schema(title = "IDs of the monitors whose effective status counted as failing")
        private final List<String> failedMonitorIds;

        @Schema(title = "Per-monitor result", description = "One entry per monitorId, in the same order. A monitor never " +
            "reached because of a timeout or a FAIL_FAST short-circuit only has monitorId set.")
        private final List<MonitorResult> monitors;
    }

    @Builder
    @Getter
    public static class MonitorResult {

        @Schema(title = "Monitor ID")
        private final String monitorId;

        @Schema(
            title = "Reported monitor status",
            description = "The raw status as returned by the API, never escalated by staleness. Null if the " +
                "monitor was never reached."
        )
        private final MonitorStatus status;

        @Schema(title = "Timestamp of the monitor's result, if reported by the API")
        private final Instant checkedAt;

        @Schema(
            title = "Whether this result is stale",
            description = "True when maxAge is set, runFirst is false, and the result is older than maxAge. A " +
                "stale result is escalated to FAIL for the gate's fail decision even though status stays truthful."
        )
        private final boolean stale;

        @Schema(title = "Per-series status", description = "Status of each of the monitor's group-by series.")
        private final List<SeriesStatus> series;
    }
}
