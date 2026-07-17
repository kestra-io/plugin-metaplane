package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Shared authentication, HTTP plumbing, and error handling for all Metaplane tasks.
 * {@link MonitorResultTrigger} is not a Task (it extends AbstractTrigger) so it cannot extend this
 * class; it reuses the static helpers below instead, the same way plugin-clevercloud's trigger
 * reuses its AbstractCleverCloudConnection statics.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMetaplaneTask extends Task {

    /**
     * Confirmed against Metaplane's official API docs (https://docs.metaplane.dev/reference): the API is
     * served from dev.api.metaplane.dev, not app.metaplane.dev (which is the web app, not the API host).
     * Kept overridable via the baseUrl property in case Metaplane introduces additional or region-specific hosts.
     */
    public static final String DEFAULT_BASE_URL = "https://dev.api.metaplane.dev";

    public static final ObjectMapper MAPPER = JacksonMapper.ofJson(false)
        .copy()
        // Tolerates status values Metaplane may introduce in the future by falling back to MonitorStatus.UNKNOWN
        // instead of throwing, since the response schema is not officially confirmed.
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);

    @Schema(
        title = "Metaplane API token",
        description = "API token used to authenticate against the Metaplane API. Generate one at " +
            "https://app.metaplane.dev/account/manage-tokens and store it as a Kestra secret."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "Metaplane API base URL",
        description = "Base endpoint for all Metaplane API calls. Defaults to `" + DEFAULT_BASE_URL + "`."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client options",
        description = "Optional HTTP configuration (timeouts, proxy, SSL) applied to every Metaplane API call."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    /**
     * Convenience wrapper around the static overload below, for Task subclasses that hold apiToken/baseUrl
     * as instance fields; MonitorResultTrigger (not a Task) calls the static overloads directly.
     */
    protected String renderApiToken(RunContext runContext) throws IllegalVariableEvaluationException {
        return renderApiToken(runContext, this.apiToken);
    }

    /**
     * Convenience wrapper around the static overload below, for Task subclasses that hold apiToken/baseUrl
     * as instance fields; MonitorResultTrigger (not a Task) calls the static overloads directly.
     */
    protected String renderBaseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return renderBaseUrl(runContext, this.baseUrl);
    }

    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws Exception {
        return request(runContext, this.options, renderApiToken(runContext), requestBuilder, responseType);
    }

    public static String renderApiToken(RunContext runContext, Property<String> apiToken) throws IllegalVariableEvaluationException {
        return runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("apiToken is required")
        );
    }

    public static String renderBaseUrl(RunContext runContext, Property<String> baseUrl) throws IllegalVariableEvaluationException {
        return runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
    }

    /**
     * Shared HTTP call logic: attaches the API key, executes the request, and on a non-2xx response
     * rewrites the failure into a clear, actionable message (never a raw stack trace).
     */
    public static <RES> HttpResponse<RES> request(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        HttpRequest.HttpRequestBuilder requestBuilder,
        Class<RES> responseType
    ) throws Exception {
        var request = requestBuilder
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", apiToken)
            .build();

        var configBuilder = options != null ? options.toBuilder() : HttpConfiguration.builder();

        try (var client = new HttpClient(runContext, configBuilder.build())) {
            var response = client.request(request, String.class);

            @SuppressWarnings("unchecked")
            RES parsedResponse = responseType == String.class
                ? (RES) response.getBody()
                : MAPPER.readValue(response.getBody() != null ? response.getBody() : "{}", responseType);

            return HttpResponse.<RES>builder()
                .request(request)
                .body(parsedResponse)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();
        } catch (HttpClientResponseException e) {
            throw rewriteError(runContext.logger(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse the Metaplane API response: " + e.getMessage(), e);
        }
    }

    private static HttpClientResponseException rewriteError(Logger logger, HttpClientResponseException e) {
        var response = e.getResponse();
        var status = response != null && response.getStatus() != null ? response.getStatus().getCode() : -1;

        logger.debug("Metaplane API call failed with HTTP {}: {}", status, e.getMessage());

        if (status == 401 || status == 403) {
            return new HttpClientResponseException(
                "Metaplane API returned HTTP " + status + ": invalid or missing API token. Verify apiToken is a " +
                    "valid, non-expired token created at https://app.metaplane.dev/account/manage-tokens",
                response, e
            );
        }

        if (status == 404) {
            return new HttpClientResponseException(
                "Metaplane API returned HTTP 404: resource not found. Verify baseUrl (e.g. " + DEFAULT_BASE_URL +
                    ") and the ID used in the request are correct.",
                response, e
            );
        }

        return new HttpClientResponseException(
            "Metaplane API request failed with HTTP " + status + ": " + e.getMessage(),
            response, e
        );
    }

    public static boolean isNotFound(HttpClientResponseException e) {
        return e.getResponse() != null && e.getResponse().getStatus() != null && e.getResponse().getStatus().getCode() == 404;
    }

    /**
     * A monitor that has never been run returns 404 on the status endpoint. Both the {@link Get} task and
     * {@link MonitorResultTrigger} surface this as an actionable failure instead of a raw HTTP exception.
     */
    public static IllegalStateException monitorNeverRunException(String monitorId, HttpClientResponseException cause) {
        return new IllegalStateException(
            "Monitor " + monitorId + " has no run history yet. Run it first, for example with the " +
                "io.kestra.plugin.metaplane.Run task, before reading its status.",
            cause
        );
    }

    /**
     * Shared status lookup used by both {@link Get} and {@link MonitorResultTrigger} so the 404
     * "never run" handling lives in a single place.
     */
    public static MonitorStatusResponse fetchMonitorStatus(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String monitorId
    ) throws Exception {
        var url = join(baseUrl, "v2/monitors/status/" + URLEncoder.encode(monitorId, StandardCharsets.UTF_8));
        var requestBuilder = HttpRequest.builder().uri(URI.create(url)).method("GET");

        try {
            return request(runContext, options, apiToken, requestBuilder, MonitorStatusResponse.class).getBody();
        } catch (HttpClientResponseException e) {
            if (isNotFound(e)) {
                throw monitorNeverRunException(monitorId, e);
            }
            throw e;
        }
    }

    /**
     * Joins a base URL and a path segment with exactly one slash, so a trailing slash on a
     * user-overridden baseUrl never produces a double slash in the request URI.
     */
    public static String join(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    /**
     * Shared FetchType handling for list tasks: FETCH keeps all items, FETCH_ONE keeps the first,
     * STORE writes the items to an ion file in internal storage, NONE keeps only the count.
     */
    protected static <T> FetchResult<T> fetchOutput(RunContext runContext, Property<FetchType> fetchType, List<T> items) throws Exception {
        var total = items.size();
        return switch (runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH)) {
            case FETCH -> new FetchResult<>(items, null, null, total);
            case FETCH_ONE -> new FetchResult<>(null, items.isEmpty() ? null : items.getFirst(), null, total);
            case STORE -> new FetchResult<>(null, null, store(runContext, items), total);
            case NONE -> new FetchResult<>(null, null, null, total);
        };
    }

    private static <T> URI store(RunContext runContext, List<T> items) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (var writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
            FileSerde.writeAll(writer, Flux.fromIterable(items)).block();
        }

        return runContext.storage().putFile(tempFile);
    }

    public record FetchResult<T>(List<T> items, T first, URI uri, int total) {
    }
}
