package com.ggar.orchid.executor;

import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LoopActionExecutor implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(LoopActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;

    public LoopActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) {
        this.spelEvaluator = spelEvaluator;
        this.i18n = i18n;
    }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        com.ggar.orchid.model.LoopAction loopAction = (com.ggar.orchid.model.LoopAction) action;
        String loopName = Optional.ofNullable(loopAction.getName()).orElse(i18n.getMessage("executor.loop.unnamed"));
        log.debug(i18n.getMessage("executor.loop.startingWithinContext", loopName, loopAction.getDescription() != null ? loopAction.getDescription() : "", lineage.toString()));

        ActionExecutionLineage innerLineage = lineage.dive(loopName);

        if (loopAction.getCollection() != null && !loopAction.getCollection().trim().isEmpty()) {
            executeCollectionLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage);
        } else if (loopAction.getFrom() != null && loopAction.getTo() != null) {
            executeNumericLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage);
        } else {
            log.error(i18n.getMessage("executor.loop.invalidConfig", loopName));
        }
        return null;
    }

    private void executeCollectionLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage) {
        Object collectionObj = spelEvaluator.evaluate(loopAction.getCollection(), parentContext, null, jobSpecificClassLoader);
        if (!(collectionObj instanceof Collection)) {
            log.error(i18n.getMessage("executor.loop.collectionNotIterable", loopName, loopAction.getCollection()));
            return;
        }
        List<?> listForIteration = new ArrayList<>((Collection<?>) collectionObj);
        int index = 0; String iteratorVar = loopAction.getIteratorVariable();
        for (Object item : listForIteration) {
            Map<String, Object> loopIterationContext = new ConcurrentHashMap<>(parentContext);
            loopIterationContext.put(iteratorVar, item);
            loopIterationContext.put(iteratorVar + "_index", index);
            log.debug(i18n.getMessage("executor.loop.iteration.collectionWithLineage", currentLineage.toString(), iteratorVar, item, index));
            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), loopIterationContext, null, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) {
                    log.error(i18n.getMessage("executor.loop.conditionError.collection", e.getMessage())); break;
                }
            }
            if (!continueLoop) {
                log.debug(i18n.getMessage("executor.loop.conditionFalse.collection")); break;
            }
            orchestratorService.executeActions(loopAction.getBody(), loopIterationContext, jobSpecificClassLoader, currentLineage); 
            index++;
        }
    }

    private void executeNumericLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage) {
        long current, max;
        try {
            current = spelEvaluator.evaluate(loopAction.getFrom(), parentContext, null, Long.class, jobSpecificClassLoader);
            max = spelEvaluator.evaluate(loopAction.getTo(), parentContext, null, Long.class, jobSpecificClassLoader);
        } catch (Exception e) {
            log.error(i18n.getMessage("executor.loop.fromToError", loopName, e.getMessage())); return;
        }
        String iteratorVar = loopAction.getIteratorVariable();
        while (true) {
            Map<String, Object> loopIterationContext = new ConcurrentHashMap<>(parentContext);
            loopIterationContext.put(iteratorVar, current);
            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), loopIterationContext, null, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) {
                    log.error(i18n.getMessage("executor.loop.conditionError.numeric", e.getMessage())); break;
                }
            } else continueLoop = current <= max;
            if (!continueLoop) {
                log.debug(i18n.getMessage("executor.loop.conditionFalse.numericWithLineage", currentLineage.toString(), iteratorVar, current)); break;
            }
            log.debug(i18n.getMessage("executor.loop.iteration.numericWithLineage", currentLineage.toString(), iteratorVar, current));
            orchestratorService.executeActions(loopAction.getBody(), loopIterationContext, jobSpecificClassLoader, currentLineage);
            if (loopAction.getIncrementExpression() != null && !loopAction.getIncrementExpression().trim().isEmpty()) {
                try {
                    Object nextValue = spelEvaluator.evaluate(loopAction.getIncrementExpression(), loopIterationContext, null, jobSpecificClassLoader);
                    if (nextValue instanceof Number) current = ((Number) nextValue).longValue();
                    else { log.error(i18n.getMessage("executor.loop.incrementError.notNumber", loopName)); break; }
                } catch (Exception e) {
                    log.error(i18n.getMessage("executor.loop.incrementError.evaluation", loopName, e.getMessage())); break;
                }
            } else current++;
            if (current > max && (loopAction.getConditionExpression() == null || loopAction.getConditionExpression().trim().isEmpty())) {
                if (current > max + 10000 && loopAction.getFrom() != null && Long.parseLong(loopAction.getFrom()) < current ) {
                    log.warn(i18n.getMessage("executor.loop.infiniteLoopGuard", iteratorVar)); break;
                }
            }
        }
    }
}