package io.kestra.plugin.metaplane;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single monitor as returned by GET /v1/monitors/connection/{connectionId}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Monitor {

    @Schema(title = "Monitor ID")
    private String id;

    @Schema(title = "Monitor name")
    private String name;

    @Schema(title = "Monitor description")
    private String description;
}
