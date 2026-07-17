package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single monitor as returned by GET /v1/monitors.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Monitor {

    private String id;

    private String name;

    private String description;
}
