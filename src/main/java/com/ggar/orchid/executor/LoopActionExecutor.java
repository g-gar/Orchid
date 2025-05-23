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
    // ... (constructor igual)
    private static final Logger log = LoggerFactory.getLogger(LoopActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;
    public LoopActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) { this.spelEvaluator = spelEvaluator; this.i18n = i18n; }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariables) {
        com.ggar.orchid.model.LoopAction loopAction = (com.ggar.orchid.model.LoopAction) action;
        String loopName = Optional.ofNullable(loopAction.getName()).orElse(i18n.getMessage("executor.loop.unnamed"));
        log.debug(i18n.getMessage("executor.loop.startingWithinContext", loopName, loopAction.getDescription() != null ? loopAction.getDescription() : "", lineage.toString()));
        ActionExecutionLineage innerLineage = lineage.dive(loopName);

        // #previousResult para el loop en sí viene de additionalSpelVariables
        // Dentro del bucle, #previousResult se manejará por la llamada recursiva a executeActions

        if (loopAction.getCollection() != null && !loopAction.getCollection().trim().isEmpty()) {
            executeCollectionLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage, additionalSpelVariables);
        } else if (loopAction.getFrom() != null && loopAction.getTo() != null) {
            executeNumericLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage, additionalSpelVariables);
        } else {
            log.error(i18n.getMessage("executor.loop.invalidConfig", loopName));
        }
        return null; // El loop en sí no devuelve un valor directo para #previousResult de la siguiente acción (a menos que se cambie)
    }

    private void executeCollectionLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage, Map<String, Object> initialAdditionalSpelVariables) {
        Object collectionObj = spelEvaluator.evaluate(loopAction.getCollection(), parentContext, initialAdditionalSpelVariables, jobSpecificClassLoader);
        if (!(collectionObj instanceof Collection)) {
            log.error(i18n.getMessage("executor.loop.collectionNotIterable", loopName, loopAction.getCollection())); return;
        }
        List<?> listForIteration = new ArrayList<>((Collection<?>) collectionObj);
        int index = 0; String iteratorVar = loopAction.getIteratorVariable();
        Object lastIterationBodyResult = null; // Para el #previousResult dentro del cuerpo del bucle

        for (Object item : listForIteration) {
            Map<String, Object> loopIterationContext = new ConcurrentHashMap<>(parentContext);
            loopIterationContext.put(iteratorVar, item); loopIterationContext.put(iteratorVar + "_index", index);
            log.debug(i18n.getMessage("executor.loop.iteration.collectionWithLineage", currentLineage.toString(), iteratorVar, item, index));

            Map<String, Object> bodyAdditionalSpelVariables = new HashMap<>(initialAdditionalSpelVariables); // Heredar variables externas
            bodyAdditionalSpelVariables.put(OrchestratorService.PREVIOUS_ACTION_RESULT_KEY, lastIterationBodyResult); // #previousResult para la 1ra acción del body

            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), loopIterationContext, bodyAdditionalSpelVariables, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.conditionError.collection", e.getMessage())); break; }
            }
            if (!continueLoop) { log.debug(i18n.getMessage("executor.loop.conditionFalse.collection")); break; }

            // executeActions ahora maneja el flujo de #previousResult internamente para las acciones en loopAction.getBody()
            orchestratorService.executeActions(loopAction.getBody(), loopIterationContext, jobSpecificClassLoader, currentLineage, lastIterationBodyResult);
            // Si necesitáramos el resultado de la *última acción del body* para la *siguiente iteración del bucle* (raro):
            // lastIterationBodyResult = <somehow_get_result_of_last_action_in_body>;
            // Por ahora, el #previousResult se reinicia (a null) para la primera acción de cada iteración del body.
            // Para hacerlo más simple, pasamos null como initialPreviousResult para el body.
            // La llamada a executeActions se actualizó para recibir initialPreviousResult
            // El loopAction en sí no produce un "previousResult" para la acción *después* del loop.
            index++;
        }
    }

    private void executeNumericLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage, Map<String, Object> initialAdditionalSpelVariables) {
        long current, max;
        try {
            current = spelEvaluator.evaluate(loopAction.getFrom(), parentContext, initialAdditionalSpelVariables, Long.class, jobSpecificClassLoader);
            max = spelEvaluator.evaluate(loopAction.getTo(), parentContext, initialAdditionalSpelVariables, Long.class, jobSpecificClassLoader);
        } catch (Exception e) { log.error(i18n.getMessage("executor.loop.fromToError", loopName, e.getMessage())); return; }
        String iteratorVar = loopAction.getIteratorVariable();
        Object lastIterationBodyResult = null;

        while (true) {
            Map<String, Object> loopIterationContext = new ConcurrentHashMap<>(parentContext);
            loopIterationContext.put(iteratorVar, current);

            Map<String, Object> bodyAdditionalSpelVariables = new HashMap<>(initialAdditionalSpelVariables);
            bodyAdditionalSpelVariables.put(OrchestratorService.PREVIOUS_ACTION_RESULT_KEY, lastIterationBodyResult);

            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), loopIterationContext, bodyAdditionalSpelVariables, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.conditionError.numeric", e.getMessage())); break; }
            } else continueLoop = current <= max;
            if (!continueLoop) { log.debug(i18n.getMessage("executor.loop.conditionFalse.numericWithLineage", currentLineage.toString(), iteratorVar, current)); break; }

            log.debug(i18n.getMessage("executor.loop.iteration.numericWithLineage", currentLineage.toString(), iteratorVar, current));
            orchestratorService.executeActions(loopAction.getBody(), loopIterationContext, jobSpecificClassLoader, currentLineage, lastIterationBodyResult);

            if (loopAction.getIncrementExpression() != null && !loopAction.getIncrementExpression().trim().isEmpty()) {
                try {
                    Object nextValue = spelEvaluator.evaluate(loopAction.getIncrementExpression(), loopIterationContext, bodyAdditionalSpelVariables, jobSpecificClassLoader);
                    if (nextValue instanceof Number) current = ((Number) nextValue).longValue();
                    else { log.error(i18n.getMessage("executor.loop.incrementError.notNumber", loopName)); break; }
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.incrementError.evaluation", loopName, e.getMessage())); break; }
            } else current++;
            if (current > max && (loopAction.getConditionExpression() == null || loopAction.getConditionExpression().trim().isEmpty())) {
                if (current > max + 10000 && loopAction.getFrom() != null && Long.parseLong(loopAction.getFrom()) < current ) {
                    log.warn(i18n.getMessage("executor.loop.infiniteLoopGuard", iteratorVar)); break;
                }
            }
        }
    }
}