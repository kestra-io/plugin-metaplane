package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Execution(ExecutionMode.SAME_THREAD)
class GateTest extends AbstractMetaplaneTest {

    private static Gate.GateBuilder<?, ?> baseGate(WireMockRuntimeInfo wireMockRuntimeInfo, List<String> monitorIds) {
        return Gate.builder()
            .id("gate-test-" + Instant.now().toEpochMilli())
            .type(Gate.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .monitorIds(Property.ofValue(monitorIds))
            .runFirst(Property.ofValue(false))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .timeout(Property.ofValue(Duration.ofSeconds(2)));
    }

    @Test
    void happyPathPasses(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1")).build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(true));
        assertThat(output.getFailedMonitorIds(), empty());
        assertThat(output.getMonitors(), hasSize(1));
        assertThat(output.getMonitors().getFirst().getMonitorId(), is("monitor-1"));
        assertThat(output.getMonitors().getFirst().getStatus(), is(MonitorStatus.PASS));
        assertThat(output.getMonitors().getFirst().getCheckedAt(), is(Instant.parse("2024-01-01T00:00:00Z")));
        assertThat(output.getMonitors().getFirst().isStale(), is(false));
        assertThat(output.getMonitors().getFirst().getSeries(), hasSize(1));
    }

    @Test
    void failIfAnyFailsWhenOneMonitorFails(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-pass", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-fail", """
            {"statuses":[{"status":"FAIL"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-pass", "monitor-fail"))
            .failStrategy(Property.ofValue(FailStrategy.FAIL_IF_ANY))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(false));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-fail")));
        assertThat(output.getMonitors(), hasSize(2));
    }

    @Test
    void failIfAllPassesWhenNotEveryMonitorFails(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-pass", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-fail", """
            {"statuses":[{"status":"FAIL"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-pass", "monitor-fail"))
            .failStrategy(Property.ofValue(FailStrategy.FAIL_IF_ALL))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(true));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-fail")));
    }

    @Test
    void failIfAllFailsWhenEveryMonitorFails(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-fail-a", """
            {"statuses":[{"status":"FAIL"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-fail-b", """
            {"statuses":[{"status":"ERROR"}],"isErrored":true,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-fail-a", "monitor-fail-b"))
            .failStrategy(Property.ofValue(FailStrategy.FAIL_IF_ALL))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(false));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-fail-a", "monitor-fail-b")));
    }

    @Test
    void noneStrategyNeverFailsButStillReportsFailures(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-pass", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-fail", """
            {"statuses":[{"status":"FAIL"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-pass", "monitor-fail"))
            .failStrategy(Property.ofValue(FailStrategy.NONE))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(true));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-fail")));
    }

    @Test
    void failFastStopsPollingRemainingMonitors(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubGetJson("/v2/monitors/status/monitor-a", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-b", """
            {"statuses":[{"status":"FAIL"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);
        stubGetJson("/v2/monitors/status/monitor-c", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2024-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-a", "monitor-b", "monitor-c"))
            .failStrategy(Property.ofValue(FailStrategy.FAIL_FAST))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(false));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-b")));
        assertThat(output.getMonitors(), hasSize(3));
        assertThat(output.getMonitors().get(0).getStatus(), is(MonitorStatus.PASS));
        assertThat(output.getMonitors().get(1).getStatus(), is(MonitorStatus.FAIL));
        assertThat(output.getMonitors().get(2).getStatus(), is((MonitorStatus) null));
        assertThat(output.getMonitors().get(2).getMonitorId(), is("monitor-c"));

        verify(0, getRequestedFor(urlPathEqualTo("/v2/monitors/status/monitor-c")));
    }

    @Test
    void pollsUntilFreshResult(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/v1/monitors/run")).willReturn(okJson("{}")));

        stubFor(get(urlPathEqualTo("/v2/monitors/status/monitor-1"))
            .inScenario("poll-until-fresh")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2000-01-01T00:00:00Z"}
                """))
            .willSetStateTo("fresh"));

        stubFor(get(urlPathEqualTo("/v2/monitors/status/monitor-1"))
            .inScenario("poll-until-fresh")
            .whenScenarioStateIs("fresh")
            .willReturn(okJson("""
                {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"%s"}
                """.formatted(Instant.now().plusSeconds(3600)))));

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .runFirst(Property.ofValue(true))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(true));
        assertThat(output.getMonitors().getFirst().getStatus(), is(MonitorStatus.PASS));
        verify(2, getRequestedFor(urlPathEqualTo("/v2/monitors/status/monitor-1")));
        verify(postRequestedFor(urlPathEqualTo("/v1/monitors/run"))
            .withRequestBody(equalToJson("{\"testIds\":[\"monitor-1\"]}")));
    }

    @Test
    void timesOutWhenResultNeverBecomesFresh(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/v1/monitors/run")).willReturn(okJson("{}")));
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"2000-01-01T00:00:00Z"}
            """);

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .runFirst(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .timeout(Property.ofValue(Duration.ofMillis(300)))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Timed out"));
        assertThat(ex.getMessage(), containsString("monitor-1"));
    }

    @Test
    void staleResultIsEscalatedToFailWithoutRunFirst(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        var staleTimestamp = Instant.now().minus(Duration.ofHours(2));
        stubGetJson("/v2/monitors/status/monitor-1", """
            {"statuses":[{"status":"PASS"}],"isErrored":false,"timestamp":"%s"}
            """.formatted(staleTimestamp));

        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .maxAge(Property.ofValue(Duration.ofHours(1)))
            .build();

        var output = task.run(runContext());

        assertThat(output.isPassed(), is(false));
        assertThat(output.getFailedMonitorIds(), is(List.of("monitor-1")));
        assertThat(output.getMonitors().getFirst().isStale(), is(true));
        assertThat(output.getMonitors().getFirst().getStatus(), is(MonitorStatus.PASS));
    }

    @Test
    void failsFastOnEmptyMonitorIds(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of()).build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("monitorIds must contain at least one monitor ID"));
    }

    @Test
    void rejectsNonPositiveTimeout(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .timeout(Property.ofValue(Duration.ZERO))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("timeout must be strictly positive"));
    }

    @Test
    void rejectsNonPositivePollInterval(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .pollInterval(Property.ofValue(Duration.ZERO))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pollInterval must be strictly positive"));
    }

    @Test
    void rejectsPollIntervalAboveUpperBound(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .pollInterval(Property.ofValue(Duration.ofHours(2)))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pollInterval must not exceed"));
    }

    @Test
    void rejectsTimeoutAboveUpperBound(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .timeout(Property.ofValue(Duration.ofHours(25)))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("timeout must not exceed"));
    }

    @Test
    void rejectsMaxAgeAboveUpperBound(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = baseGate(wireMockRuntimeInfo, List.of("monitor-1"))
            .maxAge(Property.ofValue(Duration.ofHours(25)))
            .build();

        var runContext = runContext();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("maxAge must not exceed"));
    }

    @Test
    void surfacesClearExceptionWhenMonitorNeverRan(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(get(urlPathEqualTo("/v2/monitors/status/never-run"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));

        var task = baseGate(wireMockRuntimeInfo, List.of("never-run")).build();

        var runContext = runContext();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("has no run history yet"));
    }
}
