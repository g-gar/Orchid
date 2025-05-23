package com.ggar.orchid.executor;

import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SpelActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpelActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;
    public SpelActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) { this.spelEvaluator = spelEvaluator; this.i18n = i18n; }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariables) {
        com.ggar.orchid.model.SpelAction spelAction = (com.ggar.orchid.model.SpelAction) action;
        log.debug(i18n.getMessage("executor.spel.executing", spelAction.getExpression()));
        return spelEvaluator.evaluate(spelAction.getExpression(), jobContext, additionalSpelVariables, jobSpecificClassLoader);
    }
}