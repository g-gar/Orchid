package com.ggar.orchid.executor;

import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CommandActionExecutor implements ActionExecutor {
    // ... (constructor igual)
    private static final Logger log = LoggerFactory.getLogger(CommandActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;
    public CommandActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) { this.spelEvaluator = spelEvaluator; this.i18n = i18n; }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariables) {
        com.ggar.orchid.model.CommandAction commandAction = (com.ggar.orchid.model.CommandAction) action;
        String commandName = Optional.ofNullable(commandAction.getName()).orElse(commandAction.getCommand());
        log.debug(i18n.getMessage("executor.command.executing", commandName, commandAction.getCommand()));
        List<String> commandParts = new java.util.ArrayList<>();
        commandParts.add(commandAction.getCommand());
        if (commandAction.getArgs() != null) {
            for (String arg : commandAction.getArgs()) {
                try {
                    // Usar additionalSpelVariables para que #previousResult esté disponible en los args del comando
                    Object evaluatedArg = spelEvaluator.evaluate(arg, jobContext, additionalSpelVariables, jobSpecificClassLoader);
                    commandParts.add(String.valueOf(evaluatedArg));
                } catch (Exception e) {
                    log.warn(i18n.getMessage("executor.command.argEvaluationError", arg, e.getMessage())); commandParts.add(arg);
                }
            }
        }
        log.debug(i18n.getMessage("executor.command.fullCommand", commandParts));
        // ... (resto de la ejecución del comando) ...
        try {
            ProcessBuilder pb = new ProcessBuilder(commandParts);
            pb.redirectErrorStream(true); Process process = pb.start(); StringBuilder output = new StringBuilder();
            try (InputStream processInputStream = process.getInputStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(processInputStream))) {
                String line; while ((line = reader.readLine()) != null) {
                    if (commandAction.isCaptureOutput()) output.append(line).append(System.lineSeparator());
                    log.trace(i18n.getMessage("executor.command.outputLine", line));
                }
            }
            int exitCode = process.waitFor();
            log.debug(i18n.getMessage("executor.command.finished", commandName, exitCode));
            if (exitCode != 0) log.warn(i18n.getMessage("executor.command.nonZeroExit", commandName, exitCode));
            return commandAction.isCaptureOutput() ? output.toString().trim() : null;
        } catch (IOException | InterruptedException e) {
            log.error(i18n.getMessage("executor.command.executionError", commandName, e.getMessage()), e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(i18n.getMessage("executor.command.executionError.runtime", commandName), e);
        }
    }
}