package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetTest extends AbstractMetaplaneTest {

    @Test
    void fetchesMonitorStatus(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/3fa85f64-5717-4562-b3fc-2c963f66afa6", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = Get.builder()
            .id("get-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
            .build();

        var output = task.run(runContext());

        assertThat(output.getMonitorId(), is("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
        assertThat(output.getStatus(), is(MonitorStatus.PASS));
        assertThat(output.getCheckedAt(), is(Instant.parse("2024-01-01T00:00:00Z")));
    }

    @Test
    void unknownStatusValueFallsBackToUnknown(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"SOMETHING_NEW"}],"isErrored":false}
            """);

        var task = Get.builder()
            .id("get-unknown-status-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("monitor-1"))
            .build();

        var output = task.run(runContext());

        assertThat(output.getStatus(), is(MonitorStatus.UNKNOWN));
    }

    @Test
    void isErroredOverridesSeriesStatusWithError(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"}],"isErrored":true}
            """);

        var task = Get.builder()
            .id("get-errored-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("monitor-1"))
            .build();

        var output = task.run(runContext());

        assertThat(output.getStatus(), is(MonitorStatus.ERROR));
    }

    @Test
    void overallStatusIsTheWorstAcrossSeries(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"},{"status":"FAIL"}],"isErrored":false}
            """);

        var task = Get.builder()
            .id("get-worst-status-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("monitor-1"))
            .build();

        var output = task.run(runContext());

        assertThat(output.getStatus(), is(MonitorStatus.FAIL));
    }

    @Test
    void sendsAuthorizationHeader(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"}],"isErrored":false}
            """);

        var task = Get.builder()
            .id("get-auth-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("my-secret-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("monitor-1"))
            .build();

        task.run(runContext());

        verifyAuthHeader(getRequestedFor(urlPathEqualTo("/v2/monitors/status/monitor-1")), "my-secret-token");
    }

    @Test
    void throwsClearExceptionWhenMonitorNeverRan(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(get(urlPathEqualTo("/v2/monitors/status/never-run"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));

        var task = Get.builder()
            .id("get-404-test")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorId(Property.ofValue("never-run"))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("has no run history yet"));
    }
}
