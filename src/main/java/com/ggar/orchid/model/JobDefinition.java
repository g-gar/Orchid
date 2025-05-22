package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class JobDefinition {
    private String id;
    private String description;
    private List<String> initialContextParameters; // Nombres de parámetros esperados
    private List<StageDefinition> stages;
}
