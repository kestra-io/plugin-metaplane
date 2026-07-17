package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Result of the latest run of a Metaplane monitor.
 * UNKNOWN is the fallback for any value Metaplane's API might return that isn't one of the values
 * below, since the exact status vocabulary is not officially documented.
 */
public enum MonitorStatus {
    OK,
    ANOMALY,
    ERROR,
    @JsonEnumDefaultValue
    UNKNOWN
}
