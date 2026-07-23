package io.kestra.plugin.metaplane;

/**
 * How {@link Gate} combines the effective status of several monitors into a single pass/fail
 * decision. Documented on the {@code failStrategy} property's {@code @Schema} since {@code @Schema}
 * cannot annotate an enum type directly.
 */
public enum FailStrategy {
    FAIL_FAST,
    FAIL_IF_ANY,
    FAIL_IF_ALL,
    NONE
}
