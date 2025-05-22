package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class LoopAction extends Action {
    private String from;
    private String to;
    private String incrementExpression;
    private String collection;
    private String iteratorVariable;
    private String conditionExpression;
    private List<Action> body;
}
