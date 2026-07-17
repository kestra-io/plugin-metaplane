package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListTest extends AbstractMetaplaneTest {

    private static final String MONITORS_JSON = """
        [
          {"id": "monitor-1", "name": "Row count check", "description": "Checks row count anomalies"},
          {"id": "monitor-2", "name": "Freshness check"}
        ]
        """;

    private static final String MONITORS_WRAPPED_JSON = """
        {
          "monitors": [
            {"id": "monitor-1", "name": "Row count check", "description": "Checks row count anomalies"},
            {"id": "monitor-2", "name": "Freshness check"}
          ],
          "total": 2
        }
        """;

    private static final String CONNECTION_ID = "connection-1";
    private static final String MONITORS_PATH = "/v1/monitors/connection/" + CONNECTION_ID;

    @Test
    void listsMonitors(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson(MONITORS_PATH, MONITORS_JSON);

        var task = List.builder()
            .id("list-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .build();

        var output = task.run(runContext());

        assertThat(output.getTotal(), is(2));
        assertThat(output.getMonitors(), hasSize(2));
        assertThat(output.getMonitors().get(0).getName(), is("Row count check"));
        assertThat(output.getMonitor(), nullValue());
    }

    @Test
    void listsMonitorsFromObjectWrappedResponse(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson(MONITORS_PATH, MONITORS_WRAPPED_JSON);

        var task = List.builder()
            .id("list-wrapped-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .build();

        var output = task.run(runContext());

        assertThat(output.getTotal(), is(2));
        assertThat(output.getMonitors(), hasSize(2));
        assertThat(output.getMonitors().get(0).getName(), is("Row count check"));
        assertThat(output.getMonitor(), nullValue());
    }

    @Test
    void failsWithClearMessageOnUnexpectedResponseShape(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubGetJson(MONITORS_PATH, "{\"error\": \"nope\"}");

        var task = List.builder()
            .id("list-unexpected-shape-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(exception.getMessage(), containsString("Unexpected response shape from GET /v1/monitors/connection/{connectionId}"));
    }

    @Test
    void fetchOneReturnsFirstMonitorOnly(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson(MONITORS_PATH, MONITORS_JSON);

        var task = List.builder()
            .id("list-fetch-one-test")
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext());

        assertThat(output.getTotal(), is(2));
        assertThat(output.getMonitors(), nullValue());
        assertThat(output.getMonitor().getId(), is("monitor-1"));
    }
}
