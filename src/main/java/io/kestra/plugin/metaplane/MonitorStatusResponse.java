package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response of GET /v2/monitors/status/{monitorId}, per the documented MonitorStatusResultV2 schema
 * (https://docs.metaplane.dev/reference/getmonitorstatus2). A monitor can have several group-by
 * series, each with its own status; {@link #overallStatus()} rolls them up into a single value.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonitorStatusResponse {

    private List<SeriesStatus> statuses;

    // Explicit @JsonProperty: Lombok's isErrored() getter makes Jackson's bean-naming convention strip the
    // "is" prefix and look for a JSON key named "errored" instead of the API's actual "isErrored" key.
    @JsonProperty("isErrored")
    private boolean isErrored;

    private Instant timestamp;

    private String errorMessage;

    /**
     * ERROR if the group-by query itself failed (isErrored), otherwise the worst status across all
     * series, or UNKNOWN if no series were returned.
     */
    public MonitorStatus overallStatus() {
        if (isErrored) {
            return MonitorStatus.ERROR;
        }

        if (statuses == null || statuses.isEmpty()) {
            return MonitorStatus.UNKNOWN;
        }

        return statuses.stream()
            .map(SeriesStatus::getStatus)
            .reduce(MonitorStatus::worstOf)
            .orElse(MonitorStatus.UNKNOWN);
    }
}
