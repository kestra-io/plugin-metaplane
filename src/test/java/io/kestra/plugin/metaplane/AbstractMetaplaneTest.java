package io.kestra.plugin.metaplane;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

@KestraTest
@WireMockTest
public abstract class AbstractMetaplaneTest {

    @Inject
    protected RunContextFactory runContextFactory;

    protected RunContext runContext() {
        return runContextFactory.of();
    }

    protected static void stubGetJson(String path, String jsonBody) {
        stubFor(get(urlPathEqualTo(path)).willReturn(okJson(jsonBody)));
    }

    protected static void verifyAuthHeader(RequestPatternBuilder request, String token) {
        verify(request.withHeader("Authorization", equalTo(token)));
    }
}
