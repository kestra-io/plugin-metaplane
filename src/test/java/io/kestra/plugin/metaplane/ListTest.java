package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class ListTest extends AbstractMetaplaneTest {

    private static final String MONITORS_JSON = """
        [
          {"id": "monitor-1", "name": "Row count check", "description": "Checks row count anomalies"},
          {"id": "monitor-2", "name": "Freshness check"}
        ]
        """;

    @Test
    void listsMonitors(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v1/monitors", MONITORS_JSON);

        var task = List.builder()
            .id("list-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .build();

        var output = task.run(runContext());

        assertThat(output.getTotal(), is(2));
        assertThat(output.getMonitors(), hasSize(2));
        assertThat(output.getMonitors().get(0).getName(), is("Row count check"));
        assertThat(output.getMonitor(), nullValue());
    }

    @Test
    void fetchOneReturnsFirstMonitorOnly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v1/monitors", MONITORS_JSON);

        var task = List.builder()
            .id("list-fetch-one-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext());

        assertThat(output.getTotal(), is(2));
        assertThat(output.getMonitors(), nullValue());
        assertThat(output.getMonitor().getId(), is("monitor-1"));
    }
}
