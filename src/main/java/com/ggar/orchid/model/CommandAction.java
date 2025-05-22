package com.ggar.orchid.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CommandAction extends Action {
    private String command;
    private List<String> args;
    private boolean captureOutput = false;
}
