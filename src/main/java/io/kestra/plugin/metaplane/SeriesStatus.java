package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Status of a single group-by series within a monitor's result, per the documented SeriesStatus schema
 * of GET /v2/monitors/status/{monitorId} (https://docs.metaplane.dev/reference/getmonitorstatus2).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeriesStatus {

    @Schema(title = "Observed value for this series")
    private Double result;

    @Schema(title = "Lower bound of the expected range for this series")
    private Double lowerBound;

    @Schema(title = "Upper bound of the expected range for this series")
    private Double upperBound;

    @Schema(title = "Status of this series")
    private MonitorStatus status;

    @Schema(title = "Group-by values identifying this series", description = "Shape not documented by Metaplane, kept opaque.")
    private JsonNode groups;

    @Schema(title = "IDs of incidents currently open for this series")
    private List<Integer> openRelatedIncidents;
}
