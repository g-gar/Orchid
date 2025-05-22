package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class OrchestrationConfig {
    private List<JobDefinition> jobs;
}
