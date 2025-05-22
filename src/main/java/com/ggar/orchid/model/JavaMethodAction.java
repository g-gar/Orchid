package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class JavaMethodAction extends Action {
    private String beanName;
    private String method;
    private List<Object> args;
}
