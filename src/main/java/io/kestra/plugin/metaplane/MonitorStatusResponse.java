package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response of GET /v2/monitors/status/{monitorId}. Field aliases cover the plausible key names
 * documented by Metaplane's third-party API references, since the exact response schema isn't
 * published in the official docs (https://docs.metaplane.dev/reference).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonitorStatusResponse {

    @JsonAlias({"monitorId", "testId"})
    private String id;

    private MonitorStatus status;

    @JsonAlias({"lastRunAt", "runAt"})
    private Instant checkedAt;
}
