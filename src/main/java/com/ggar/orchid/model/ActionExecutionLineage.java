package com.ggar.orchid.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public record ActionExecutionLineage(String rootStageName, List<String> parentActionStack) {
    public ActionExecutionLineage(String rootStageName) {
        this(rootStageName, Collections.emptyList());
    }

    public ActionExecutionLineage dive(String currentActionName) {
        List<String> newStack = new ArrayList<>(parentActionStack);
        newStack.add(currentActionName);
        return new ActionExecutionLineage(rootStageName, Collections.unmodifiableList(newStack));
    }

    @Override
    public String toString() {
        if (parentActionStack.isEmpty()) {
            return String.format("Stage: '%s'", rootStageName);
        }
        return String.format("Stage: '%s' > %s",
                rootStageName,
                parentActionStack.stream().collect(Collectors.joining(" > ")));
    }
}