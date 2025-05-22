package com.ggar.orchid.executor;

import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

public class ConditionalActionExecutor implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(ConditionalActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;

    public ConditionalActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) {
        this.spelEvaluator = spelEvaluator;
        this.i18n = i18n;
    }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        com.ggar.orchid.model.ConditionalAction conditionalAction = (com.ggar.orchid.model.ConditionalAction) action;
        String conditionName = Optional.ofNullable(conditionalAction.getName()).orElse(i18n.getMessage("executor.conditional.unnamed"));
        log.debug(i18n.getMessage("executor.conditional.evaluatingWithLineage", conditionName, conditionalAction.getCondition(), lineage.toString()));
        boolean conditionResult = false;
        try {
            conditionResult = spelEvaluator.evaluate(conditionalAction.getCondition(), jobContext, null, Boolean.class, jobSpecificClassLoader);
        } catch (Exception e) {
            log.error(i18n.getMessage("executor.conditional.evaluationError", conditionalAction.getCondition(), e.getMessage()));
        }

        
        ActionExecutionLineage innerLineage = lineage.dive(conditionName);

        if (conditionResult) {
            log.debug(i18n.getMessage("executor.conditional.true", conditionalAction.getCondition()));
            orchestratorService.executeActions(conditionalAction.getThenActions(), jobContext, jobSpecificClassLoader, innerLineage);
        } else {
            log.debug(i18n.getMessage("executor.conditional.false", conditionalAction.getCondition()));
            
        }
        return null;
    }
}