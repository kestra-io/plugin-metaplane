package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Metaplane monitors",
    description = "Returns the monitors defined in the Metaplane workspace."
)
@Plugin(
    examples = {
        @Example(
            title = "List Metaplane monitors and log the total count",
            full = true,
            code = """
                id: metaplane_list_monitors
                namespace: company.team

                tasks:
                  - id: list_monitors
                    type: io.kestra.plugin.metaplane.List
                    apiToken: "{{ secret('METAPLANE_API_TOKEN') }}"
                  - id: log_results
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.list_monitors.total }} monitors"
                """
        )
    }
)
public class List extends AbstractMetaplaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "How to fetch the results",
        description = "FETCH returns all monitors, FETCH_ONE returns the first monitor, STORE saves them to " +
            "internal storage as an ion file, NONE returns only the count. Defaults to FETCH."
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rBaseUrl = renderBaseUrl(runContext);
        var url = join(rBaseUrl, "v1/monitors");

        logger.info("Listing Metaplane monitors");

        var requestBuilder = HttpRequest.builder().uri(URI.create(url)).method("GET");
        var body = request(runContext, requestBuilder, String.class).getBody();
        var monitors = MAPPER.readValue(body != null ? body : "[]", new TypeReference<ArrayList<Monitor>>() {
        });

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
