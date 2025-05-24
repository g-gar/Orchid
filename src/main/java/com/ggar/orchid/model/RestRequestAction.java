package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class RestRequestAction extends Action {

    private String url;
    private String method;
    private Map<String, String> headers;
    private String body;
    private String mapToType;

}
