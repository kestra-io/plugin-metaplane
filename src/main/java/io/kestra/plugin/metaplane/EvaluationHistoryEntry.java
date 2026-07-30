package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One record from POST /v1/monitors/evaluation-history/{monitorId}
 * (https://docs.metaplane.dev/reference/getevaluationhistory). Used by {@link Gate}'s per-group
 * evaluation to read each group's latest evaluation timestamp, since the v2 status endpoint carries
 * only a single monitor-level timestamp and cannot tell a live group from a stale "ghost" one.
 *
 * <p>Only the two fields Gate needs are mapped. The endpoint also returns result, lowerBound,
 * upperBound, predicted, passed, openRelatedIncidents, errorMessage, and annotation, which are ignored.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationHistoryEntry {

    private MonitorStatus status;

    private Instant createdAt;
}
