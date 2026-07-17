package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List monitors of a Metaplane connection",
    description = "Returns the monitors defined for a given Metaplane connection."
)
@Plugin(
    examples = {
        @Example(
            title = "List the monitors of a Metaplane connection and log the total count",
            full = true,
            code = """
                id: metaplane_list_monitors
                namespace: company.team

                tasks:
                  - id: list_monitors
                    type: io.kestra.plugin.metaplane.List
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                    connectionId: "3fa85f64-5717-4562-b3fc-2c963f66afa6"
                  - id: log_results
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.list_monitors.total }} monitors"
                """
        )
    }
)
public class List extends AbstractMetaplaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Connection ID",
        description = "UUID of the Metaplane connection whose monitors to list."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectionId;

    @Schema(
        title = "Include disabled monitors",
        description = "Whether monitors that are currently disabled should be included in the results. Defaults to false."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> includeDisabled = Property.ofValue(false);

    @Schema(
        title = "Fetch monitor groups",
        description = "Whether each monitor's group information should be included in the results. Defaults to false."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Boolean> fetchGroups = Property.ofValue(false);

    @Schema(
        title = "How to fetch the results",
        description = "FETCH returns all monitors, FETCH_ONE returns the first monitor, STORE saves them to " +
            "internal storage as an ion file, NONE returns only the count. Defaults to FETCH."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    /**
     * The exact shape of GET /v1/monitors/connection/{connectionId} isn't confirmed by Metaplane's official docs,
     * so this tolerates both a bare JSON array and an object wrapping the array under a "monitors" key, instead
     * of assuming one shape and crashing with a raw Jackson error on the other.
     */
    private static final String MONITORS_FIELD = "monitors";

    private static ArrayList<Monitor> parseMonitors(JsonNode node) {
        if (node.isArray()) {
            return MAPPER.convertValue(node, new TypeReference<ArrayList<Monitor>>() {
            });
        }

        if (node.isObject() && node.path(MONITORS_FIELD).isArray()) {
            return MAPPER.convertValue(node.get(MONITORS_FIELD), new TypeReference<ArrayList<Monitor>>() {
            });
        }

        throw new IllegalStateException(
            "Unexpected response shape from GET /v1/monitors/connection/{connectionId}: expected a JSON array or " +
                "an object containing a \"" + MONITORS_FIELD + "\" array, got " + node.getNodeType()
        );
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rConnectionId = runContext.render(connectionId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("connectionId is required")
        );
        var rIncludeDisabled = runContext.render(includeDisabled).as(Boolean.class).orElse(false);
        var rFetchGroups = runContext.render(fetchGroups).as(Boolean.class).orElse(false);

        var rBaseUrl = renderBaseUrl(runContext);
        var url = join(rBaseUrl, "v1/monitors/connection/" + URLEncoder.encode(rConnectionId, StandardCharsets.UTF_8))
            + "?includeDisabled=" + rIncludeDisabled + "&fetchGroups=" + rFetchGroups;

        logger.info("Listing Metaplane monitors for connection {}", rConnectionId);

        var requestBuilder = HttpRequest.builder().uri(URI.create(url)).method("GET");
        var body = request(runContext, requestBuilder, String.class).getBody();
        var monitors = parseMonitors(MAPPER.readTree(body != null ? body : "[]"));

        logger.info("Found {} monitor(s)", monitors.size());

        var result = fetchOutput(runContext, fetchType, monitors);
        return Output.builder()
            .monitors(result.items())
            .monitor(result.first())
            .uri(result.uri())
            .total(result.total())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Monitors in the workspace", description = "Populated when fetchType is FETCH.")
        private final java.util.List<Monitor> monitors;

        @Schema(title = "First monitor returned by the API", description = "Populated when fetchType is FETCH_ONE.")
        private final Monitor monitor;

        @Schema(title = "URI of the stored monitors", description = "Populated when fetchType is STORE, points to an ion file in Kestra internal storage.")
        private final URI uri;

        @Schema(title = "Total number of monitors returned")
        private final int total;
    }
}
