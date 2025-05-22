package com.ggar.orchid.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class StageDefinition {
    private String name;
    private String description;

    @JsonProperty("actions")
    private List<Action> subActions;

    @JsonUnwrapped
    private Action actionDefinition;

    public boolean isSingleActionStage() {
        return actionDefinition != null && (subActions == null || subActions.isEmpty());
    }

    public boolean isSubActionsStage() {
        return subActions != null && !subActions.isEmpty();
    }
}
