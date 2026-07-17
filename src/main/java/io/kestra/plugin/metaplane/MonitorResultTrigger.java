package io.kestra.plugin.metaplane;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
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
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fire an execution when a Metaplane monitor's status changes",
    description = """
        Polls the status of a single monitor at each interval and fires only when the status differs
        from the last-seen value, so an unchanged status never re-fires the trigger every interval.
        The first evaluation only establishes the baseline status and does not fire.

        Fails with a clear error if the monitor has never been run (HTTP 404).
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Fire when a Metaplane monitor's status changes",
            full = true,
            code = """
                id: metaplane_on_result
                namespace: company.team

                triggers:
                  - id: on_monitor_result
                    type: io.kestra.plugin.metaplane.MonitorResultTrigger
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorId: "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                    interval: PT5M

                tasks:
                  - id: handle_result
                    type: io.kestra.plugin.core.log.Log
                    message: "Monitor result: {{ trigger.status }}"
                """
        )
    }
)
public class MonitorResultTrigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<MonitorResultTrigger.Output> {

    @Schema(
        title = "Metaplane API token",
        description = "API token used to authenticate against the Metaplane API. Generate one at " +
            "https://app.metaplane.dev/account/manage-tokens and store it as a Kestra secret."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Metaplane API base URL",
        description = "Base endpoint for all Metaplane API calls. Defaults to `" + AbstractMetaplaneTask.DEFAULT_BASE_URL + "`."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(AbstractMetaplaneTask.DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client options",
        description = "Optional HTTP configuration (timeouts, proxy, SSL) applied to every Metaplane API call."
    )
    @PluginProperty(group = "advanced")
    private HttpConfiguration options;

    @Schema(
        title = "Monitor ID",
        description = "UUID of the Metaplane monitor to watch."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> monitorId;

    @Schema(
        title = "How often to check the monitor's status",
        description = "ISO-8601 duration. Defaults to PT5M."
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Duration interval = Duration.ofMinutes(5);

    @Override
    public Duration getInterval() {
        return interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rApiToken = AbstractMetaplaneTask.renderApiToken(runContext, apiToken);
        var rBaseUrl = AbstractMetaplaneTask.renderBaseUrl(runContext, baseUrl);
        var rMonitorId = runContext.render(monitorId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("monitorId is required")
        );

        logger.debug("Polling Metaplane monitor status for {}", rMonitorId);
        var status = AbstractMetaplaneTask.fetchMonitorStatus(runContext, options, rApiToken, rBaseUrl, rMonitorId);

        // Key includes flowId so triggers sharing a triggerId across flows don't clobber each other's baseline.
        var kvKey = "monitor-result-trigger-" + context.getFlowId() + "-" + context.getTriggerId() + "-" + rMonitorId;
        var kv = runContext.namespaceKv(context.getNamespace());

        // Refreshed every poll while the trigger is active, so a 10x-interval TTL never expires a live
        // baseline but ages out an orphaned entry a few polls after the trigger stops.
        var baselineTtl = interval.multipliedBy(10);

        var previousStatus = kv.getValue(kvKey);
        var overallStatus = status.overallStatus();

        if (previousStatus.isEmpty()) {
            logger.info("Establishing status baseline for monitor {}: {}", rMonitorId, overallStatus);
            persistStatus(kv, kvKey, overallStatus, baselineTtl);
            return Optional.empty();
        }

        if (overallStatus.name().equals(previousStatus.get().value())) {
            return Optional.empty();
        }

        persistStatus(kv, kvKey, overallStatus, baselineTtl);

        logger.info("Monitor {} status changed to {}", rMonitorId, overallStatus);

        var output = Output.builder()
            .monitorId(rMonitorId)
            .status(overallStatus)
            .checkedAt(status.getTimestamp())
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private static void persistStatus(KVStore kv, String kvKey, MonitorStatus status, Duration ttl) throws Exception {
        kv.put(kvKey, new KVValueAndMetadata(new KVMetadata(null, ttl), status.name()));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Monitor ID that was polled")
        private final String monitorId;

        @Schema(title = "Monitor status when the trigger fired")
        private final MonitorStatus status;

        @Schema(title = "Timestamp of the monitor's most recent run, if reported by the API")
        private final Instant checkedAt;
    }
}
