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

import java.time.Instant;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get the latest result of a Metaplane monitor",
    description = """
        Reads the current status of a monitor from the Metaplane API. This is a pure read: it never
        fails or halts the flow because of an anomaly, it only reports the status. Use a downstream
        task, such as io.kestra.plugin.core.execution.Fail with a condition on the status output, to
        gate the pipeline.

        Fails with a clear error if the monitor has never been run (HTTP 404).
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a Metaplane monitor, wait for it to complete, then gate the pipeline on its result",
            full = true,
            code = """
                id: metaplane_gate
                namespace: company.team

                tasks:
                  - id: run_monitor
                    type: io.kestra.plugin.metaplane.Run
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorIds:
                      - "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                  - id: wait_for_monitor_run
                    type: io.kestra.plugin.core.flow.Pause
                    # Run only enqueues the monitor and does not wait for completion; this pause gives it
                    # time to finish before Get reads its status. For a guaranteed-fresh result instead of
                    # a fixed wait, react to io.kestra.plugin.metaplane.MonitorResultTrigger in a separate flow.
                    pauseDuration: PT1M
                  - id: get_result
                    type: io.kestra.plugin.metaplane.Get
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorId: "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                  - id: halt_on_anomaly
                    type: io.kestra.plugin.core.execution.Fail
                    condition: "{{ outputs.get_result.status == 'FAIL' }}"
                """
        )
    }
)
public class Get extends AbstractMetaplaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Monitor ID",
        description = "UUID of the Metaplane monitor to read the status of."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> monitorId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rMonitorId = runContext.render(monitorId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("monitorId is required")
        );
        var rApiToken = renderApiToken(runContext);
        var rBaseUrl = renderBaseUrl(runContext);

        logger.info("Fetching Metaplane monitor status for {}", rMonitorId);
        var status = fetchMonitorStatus(runContext, this.options, rApiToken, rBaseUrl, rMonitorId);

        logger.info("Monitor {} status: {}", rMonitorId, status.overallStatus());

        return Output.builder()
            .monitorId(rMonitorId)
            .status(status.overallStatus())
            .checkedAt(status.getTimestamp())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Monitor ID that was read")
        private final String monitorId;

        @Schema(
            title = "Overall monitor status",
            description = "The worst status across all of the monitor's group-by series, or ERROR if the " +
                "underlying query itself failed. One of PASS (no anomaly), FAIL (anomaly detected), " +
                "IN_TRAINING, NOT_ENOUGH_DATA, FAILED_TO_PREDICT, INVALID_INPUT, ERROR, or UNKNOWN for any " +
                "value not recognized by this task."
        )
        private final MonitorStatus status;

        @Schema(title = "Timestamp of the monitor's most recent run, if reported by the API")
        private final Instant checkedAt;
    }
}
