package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunTest extends AbstractMetaplaneTest {

    @Test
    void enqueuesMonitorsToRun(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/v1/monitors/run")).willReturn(okJson("{}")));

        var task = Run.builder()
            .id("run-test")
            .type(Run.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorIds(Property.ofValue(List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6")))
            .build();

        var output = task.run(runContext());

        assertThat(output.getMonitorIds(), containsInAnyOrder("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
        verify(postRequestedFor(urlPathEqualTo("/v1/monitors/run"))
            .withRequestBody(equalToJson("{\"testIds\":[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]}")));
    }

    @Test
    void sendsBearerAuthorizationHeader(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/v1/monitors/run")).willReturn(okJson("{}")));

        var task = Run.builder()
            .id("run-auth-test")
            .type(Run.class.getName())
            .apiToken(Property.ofValue("my-secret-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorIds(Property.ofValue(List.of("monitor-1")))
            .build();

        task.run(runContext());

        verifyBearerAuth(postRequestedFor(urlPathEqualTo("/v1/monitors/run")), "my-secret-token");
    }

    @Test
    void failsFastOnEmptyMonitorIds(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = Run.builder()
            .id("run-empty-test")
            .type(Run.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorIds(Property.ofValue(List.of()))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("monitorIds must contain at least one monitor ID"));
    }

    @Test
    void throwsCleanExceptionOnInvalidApiToken(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(post(urlPathEqualTo("/v1/monitors/run"))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"invalid token\"}")));

        var task = Run.builder()
            .id("run-401-test")
            .type(Run.class.getName())
            .apiToken(Property.ofValue("bad-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorIds(Property.ofValue(List.of("monitor-1")))
            .build();

        var runContext = runContext();
        var ex = assertThrows(HttpClientResponseException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("401"));
        assertThat(ex.getMessage(), containsString("invalid or missing API token"));
    }
}
