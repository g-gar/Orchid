package com.ggar.orchid.model;

// Import RestAction
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = SpelAction.class, name = "spel"),
        @JsonSubTypes.Type(value = LoopAction.class, name = "loop"),
        @JsonSubTypes.Type(value = ConditionalAction.class, name = "conditional"),
        @JsonSubTypes.Type(value = CommandAction.class, name = "command"),
        @JsonSubTypes.Type(value = JavaMethodAction.class, name = "javaMethod"),
        @JsonSubTypes.Type(value = RestRequestAction.class, name = "restRequest") // Updated to RestRequestAction
})
@Getter
@Setter
@NoArgsConstructor
public abstract class Action {
    // Ensure RestRequestAction is imported if not already (though it's in the same package, explicit import is good practice)
    // import com.ggar.orchid.model.RestRequestAction; // Not strictly needed if in same package and no naming conflict

    private String name;
    private String description;
    private String type;
    private String returnToContextAs;
}
