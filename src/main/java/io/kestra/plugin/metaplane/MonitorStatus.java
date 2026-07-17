package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Result of a Metaplane monitor's run, per the documented schema of GET /v2/monitors/status/{monitorId}
 * (https://docs.metaplane.dev/reference/getmonitorstatus2, MonitorStatusResultV2 / SeriesStatus).
 * UNKNOWN is the fallback for any value the API returns that isn't one of the documented ones.
 *
 * Declared least to most severe: picking the worst status across multiple series (see
 * {@link #worstOf(MonitorStatus, MonitorStatus)}) never silently treats an anomaly (FAIL) or an
 * unrecognized value (UNKNOWN) as safe.
 */
public enum MonitorStatus {
    PASS,
    IN_TRAINING,
    NOT_ENOUGH_DATA,
    FAILED_TO_PREDICT,
    INVALID_INPUT,
    ERROR,
    FAIL,
    @JsonEnumDefaultValue
    UNKNOWN;

    int severity() {
        return ordinal();
    }

    static MonitorStatus worstOf(MonitorStatus a, MonitorStatus b) {
        return a.severity() >= b.severity() ? a : b;
    }
}
