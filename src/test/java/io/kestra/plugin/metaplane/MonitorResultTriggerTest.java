package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextInitializer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonitorResultTriggerTest extends AbstractMetaplaneTest {

    @Inject
    RunContextInitializer runContextInitializer;

    private String triggerId;

    private String buildTriggerId() {
        return "trigger-monitor-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private MonitorResultTrigger buildTrigger(String baseUrl) {
        return MonitorResultTrigger.builder()
            .id(triggerId)
            .type(MonitorResultTrigger.class.getName())
            .apiToken(Property.ofValue("test-api-token"))
            .baseUrl(Property.ofValue(baseUrl))
            .monitorId(Property.ofValue("monitor-1"))
            .interval(Duration.ofMinutes(1))
            .build();
    }

    private ConditionContext conditionContext(MonitorResultTrigger trigger, TriggerContext triggerContext, String flowId) throws Exception {
        var flow = Flow.builder()
            .id(flowId)
            .namespace("company.team")
            .tenantId("test-tenant")
            .build();
        var baseRunContext = (DefaultRunContext) runContextFactory.of(flow, trigger);
        var runContext = runContextInitializer.forScheduler(baseRunContext, triggerContext, trigger);
        return ConditionContext.builder()
            .runContext(runContext)
            .flow(flow)
            .build();
    }

    private TriggerContext triggerContext(String flowId) {
        return TriggerContext.builder()
            .tenantId("test-tenant")
            .namespace("company.team")
            .flowId(flowId)
            .triggerId(triggerId)
            .date(ZonedDateTime.now())
            .build();
    }

    @Test
    void firstEvaluationStoresBaselineAndDoesNotFire(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        triggerId = buildTriggerId();
        stubGetJson("/v2/monitors/status/monitor-1", "{\"statuses\":[{\"status\":\"PASS\"}],\"isErrored\":false}");

        var trigger = buildTrigger(wireMockRuntimeInfo.getHttpBaseUrl());
        var trigCtx = triggerContext("test-flow");
        var condCtx = conditionContext(trigger, trigCtx, "test-flow");

        var result = trigger.evaluate(condCtx, trigCtx);

        assertThat("first evaluation must not fire", result.isEmpty(), is(true));
    }

    @Test
    void doesNotRefireWhenStatusUnchanged(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        triggerId = buildTriggerId();
        stubGetJson("/v2/monitors/status/monitor-1", "{\"statuses\":[{\"status\":\"PASS\"}],\"isErrored\":false}");

        var trigger = buildTrigger(wireMockRuntimeInfo.getHttpBaseUrl());
        var trigCtx = triggerContext("test-flow");
        var condCtx = conditionContext(trigger, trigCtx, "test-flow");

        trigger.evaluate(condCtx, trigCtx);
        var result = trigger.evaluate(condCtx, trigCtx);

        assertThat("trigger must not re-fire on an unchanged status", result.isEmpty(), is(true));
    }

    @Test
    void firesWhenStatusChanges(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        triggerId = buildTriggerId();
        stubFor(get(urlPathEqualTo("/v2/monitors/status/monitor-1"))
            .inScenario("status-change")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"statuses\":[{\"status\":\"PASS\"}],\"isErrored\":false}"))
            .willSetStateTo("baseline-set"));
        stubFor(get(urlPathEqualTo("/v2/monitors/status/monitor-1"))
            .inScenario("status-change")
            .whenScenarioStateIs("baseline-set")
            .willReturn(okJson("{\"statuses\":[{\"status\":\"FAIL\"}],\"isErrored\":false}")));

        var trigger = buildTrigger(wireMockRuntimeInfo.getHttpBaseUrl());
        var trigCtx = triggerContext("test-flow");
        var condCtx = conditionContext(trigger, trigCtx, "test-flow");

        trigger.evaluate(condCtx, trigCtx);
        Optional<Execution> result = trigger.evaluate(condCtx, trigCtx);

        assertThat("trigger must fire when the status changes", result.isPresent(), is(true));
        @SuppressWarnings("unchecked")
        var triggerVars = (Map<String, Object>) result.get().getTrigger().getVariables();
        assertThat(triggerVars.get("status"), is("FAIL"));
        assertThat(triggerVars.get("monitorId"), is("monitor-1"));
    }

    @Test
    void throwsClearExceptionWhenMonitorNeverRan(WireMockRuntimeInfo wireMockRuntimeInfo) {
        triggerId = buildTriggerId();
        stubFor(get(urlPathEqualTo("/v2/monitors/status/monitor-1"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));

        var trigger = buildTrigger(wireMockRuntimeInfo.getHttpBaseUrl());
        var trigCtx = triggerContext("test-flow");

        var ex = assertThrows(IllegalStateException.class, () -> {
            var condCtx = conditionContext(trigger, trigCtx, "test-flow");
            trigger.evaluate(condCtx, trigCtx);
        });
        assertThat(ex.getMessage(), containsString("has no run history yet"));
    }
}
