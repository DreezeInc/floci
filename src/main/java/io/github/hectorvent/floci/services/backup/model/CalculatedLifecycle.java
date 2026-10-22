package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CalculatedLifecycle {

    @JsonProperty("MoveToColdStorageAt")
    private Long moveToColdStorageAt;

    @JsonProperty("DeleteAt")
    private Long deleteAt;

    public CalculatedLifecycle() {}

    public CalculatedLifecycle(Long moveToColdStorageAt, Long deleteAt) {
        this.moveToColdStorageAt = moveToColdStorageAt;
        this.deleteAt = deleteAt;
    }

    public Long getMoveToColdStorageAt() { return moveToColdStorageAt; }
    public void setMoveToColdStorageAt(Long moveToColdStorageAt) { this.moveToColdStorageAt = moveToColdStorageAt; }

    public Long getDeleteAt() { return deleteAt; }
    public void setDeleteAt(Long deleteAt) { this.deleteAt = deleteAt; }
}
