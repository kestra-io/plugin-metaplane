package io.kestra.plugin.metaplane;

import io.kestra.core.http.HttpRequest;
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

import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run one or more Metaplane monitors now",
    description = """
        Enqueues the given monitors to run immediately through the Metaplane API.
        The API responds as soon as the run is enqueued (HTTP 200), not once it has completed, and
        does not return a run identifier. Use the Get task or the MonitorResultTrigger afterwards to
        read the resulting status once the monitor has finished running.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a Metaplane monitor and gate the pipeline on its result",
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
                  - id: get_result
                    type: io.kestra.plugin.metaplane.Get
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    monitorId: "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                  - id: halt_on_anomaly
                    type: io.kestra.plugin.core.execution.Fail
                    condition: "{{ outputs.get_result.status == 'ANOMALY' }}"
                """
        )
    }
)
public class Run extends AbstractMetaplaneTask implements RunnableTask<Run.Output> {

    @Schema(
        title = "Monitor IDs to run",
        description = "UUIDs of the Metaplane monitors to enqueue for an immediate run. Must contain at least one ID."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> monitorIds;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rMonitorIds = runContext.render(monitorIds).asList(String.class);
        if (rMonitorIds.isEmpty()) {
            throw new IllegalArgumentException("monitorIds must contain at least one monitor ID");
        }

        var rBaseUrl = renderBaseUrl(runContext);
        var url = join(rBaseUrl, "v1/monitors/run");

        logger.info("Enqueuing {} Metaplane monitor(s) to run: {}", rMonitorIds.size(), rMonitorIds);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.of(Map.of("testIds", rMonitorIds)));

        // The response body is not used: the API only confirms enqueueing (HTTP 200), it does not return a run id.
        request(runContext, requestBuilder, String.class);

        logger.info("Monitor(s) enqueued successfully");

        return Output.builder()
            .monitorIds(rMonitorIds)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Monitor IDs that were enqueued to run")
        private final List<String> monitorIds;
    }
}
