package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One record from POST /v1/monitors/evaluation-history/{monitorId}. {@link Gate}'s per-group mode uses
 * it to read a group's latest evaluation time; other response fields are ignored.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationHistoryEntry {

    private MonitorStatus status;

    private Instant createdAt;
}
