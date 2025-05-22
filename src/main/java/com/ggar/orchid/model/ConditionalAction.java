package com.ggar.orchid.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ConditionalAction extends Action {
    private String condition;
    @JsonProperty("actions")
    private List<Action> thenActions;
}

