package com.ggar.orchid.model;

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
        @JsonSubTypes.Type(value = JavaMethodAction.class, name = "javaMethod")
})
@Getter
@Setter
@NoArgsConstructor
public abstract class Action {
    private String name;
    private String description;
    private String type;
    private String returnToContextAs;
}
