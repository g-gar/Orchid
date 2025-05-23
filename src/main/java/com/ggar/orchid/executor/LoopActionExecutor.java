package com.ggar.orchid.executor;

import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class LoopActionExecutor implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(LoopActionExecutor.class);
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;
    public LoopActionExecutor(SpelExpressionEvaluator spelEvaluator, I18nService i18n) { this.spelEvaluator = spelEvaluator; this.i18n = i18n; }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariablesFromParent) {
        com.ggar.orchid.model.LoopAction loopAction = (com.ggar.orchid.model.LoopAction) action;
        String loopName = Optional.ofNullable(loopAction.getName()).orElse(i18n.getMessage("executor.loop.unnamed"));
        log.debug(i18n.getMessage("executor.loop.startingWithinContext", loopName, loopAction.getDescription() != null ? loopAction.getDescription() : "", lineage.toString()));
        ActionExecutionLineage innerLineage = lineage.dive(loopName);

        // El #previousResult para la primera evaluación del loop (ej. 'collection' o 'from')
        // viene de additionalSpelVariablesFromParent
        if (loopAction.getCollection() != null && !loopAction.getCollection().trim().isEmpty()) {
            executeCollectionLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage, additionalSpelVariablesFromParent);
        } else if (loopAction.getFrom() != null && loopAction.getTo() != null) {
            executeNumericLoop(loopAction, jobContext, orchestratorService, loopName, jobSpecificClassLoader, innerLineage, additionalSpelVariablesFromParent);
        } else {
            log.error(i18n.getMessage("executor.loop.invalidConfig", loopName));
        }
        // Un loop por sí mismo no produce un #previousResult para la siguiente acción *hermana*.
        // Si se quisiera, se tendría que definir explícitamente.
        return null;
    }

    private void executeCollectionLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage, Map<String, Object> initialAdditionalSpelVariables) {
        Object collectionObj = spelEvaluator.evaluate(loopAction.getCollection(), parentContext, initialAdditionalSpelVariables, jobSpecificClassLoader);
        if (!(collectionObj instanceof Collection)) {
            log.error(i18n.getMessage("executor.loop.collectionNotIterable", loopName, loopAction.getCollection())); return;
        }
        List<?> listForIteration = new ArrayList<>((Collection<?>) collectionObj);
        int index = 0; String iteratorVar = loopAction.getIteratorVariable();
        // El #previousResult para la primera acción DENTRO del body de la primera iteración
        // debería ser el #previousResult que este loop recibió (initialAdditionalSpelVariables).
        Object previousResultForBody = initialAdditionalSpelVariables.get(OrchestratorService.PREVIOUS_ACTION_RESULT_KEY);

        for (Object item : listForIteration) {
            // Usar parentContext directamente para que las modificaciones persistan
            parentContext.put(iteratorVar, item);
            parentContext.put(iteratorVar + "_index", index);
            log.debug(i18n.getMessage("executor.loop.iteration.collectionWithLineage", currentLineage.toString(), iteratorVar, item, index));

            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    // La condición se evalúa con el parentContext (que incluye iteratorVar)
                    // y el previousResult de la acción ANTERIOR a este loop
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), parentContext, initialAdditionalSpelVariables, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.conditionError.collection", e.getMessage())); break; }
            }
            if (!continueLoop) { log.debug(i18n.getMessage("executor.loop.conditionFalse.collection")); break; }

            // La llamada a executeActions manejará el flujo de #previousResult para las acciones DENTRO del body.
            // Se le pasa el 'previousResultForBody' actual, que se actualizará con el resultado de la última acción del body.
            previousResultForBody = orchestratorService.executeActions(loopAction.getBody(), parentContext, jobSpecificClassLoader, currentLineage, previousResultForBody);

            index++;
        }
        // Limpiar variables del iterador del contexto
        parentContext.remove(iteratorVar);
        parentContext.remove(iteratorVar + "_index");
    }

    private void executeNumericLoop(com.ggar.orchid.model.LoopAction loopAction, Map<String, Object> parentContext, OrchestratorService orchestratorService, String loopName, ClassLoader jobSpecificClassLoader, ActionExecutionLineage currentLineage, Map<String, Object> initialAdditionalSpelVariables) {
        long current, max;
        try {
            current = spelEvaluator.evaluate(loopAction.getFrom(), parentContext, initialAdditionalSpelVariables, Long.class, jobSpecificClassLoader);
            max = spelEvaluator.evaluate(loopAction.getTo(), parentContext, initialAdditionalSpelVariables, Long.class, jobSpecificClassLoader);
        } catch (Exception e) { log.error(i18n.getMessage("executor.loop.fromToError", loopName, e.getMessage())); return; }
        String iteratorVar = loopAction.getIteratorVariable();
        Object previousResultForBody = initialAdditionalSpelVariables.get(OrchestratorService.PREVIOUS_ACTION_RESULT_KEY);

        while (true) {
            parentContext.put(iteratorVar, current);

            boolean continueLoop = true;
            if (loopAction.getConditionExpression() != null && !loopAction.getConditionExpression().trim().isEmpty()) {
                try {
                    // La condición se evalúa con el parentContext (que incluye iteratorVar)
                    // y el #previousResult que este loop recibió
                    continueLoop = spelEvaluator.evaluate(loopAction.getConditionExpression(), parentContext, initialAdditionalSpelVariables, Boolean.class, jobSpecificClassLoader);
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.conditionError.numeric", e.getMessage())); break; }
            } else continueLoop = current <= max;
            if (!continueLoop) { log.debug(i18n.getMessage("executor.loop.conditionFalse.numericWithLineage", currentLineage.toString(), iteratorVar, current)); break; }

            log.debug(i18n.getMessage("executor.loop.iteration.numericWithLineage", currentLineage.toString(), iteratorVar, current));
            previousResultForBody = orchestratorService.executeActions(loopAction.getBody(), parentContext, jobSpecificClassLoader, currentLineage, previousResultForBody);

            if (loopAction.getIncrementExpression() != null && !loopAction.getIncrementExpression().trim().isEmpty()) {
                try {
                    Map<String, Object> incrementSpelVars = new HashMap<>(parentContext); // Asegurar que #currentNumber esté disponible
                    if (initialAdditionalSpelVariables != null) incrementSpelVars.putAll(initialAdditionalSpelVariables); // Pasar también #previousResult global

                    Object nextValue = spelEvaluator.evaluate(loopAction.getIncrementExpression(), incrementSpelVars, Collections.singletonMap(OrchestratorService.PREVIOUS_ACTION_RESULT_KEY, previousResultForBody), jobSpecificClassLoader);
                    if (nextValue instanceof Number) current = ((Number) nextValue).longValue();
                    else { log.error(i18n.getMessage("executor.loop.incrementError.notNumber", loopName)); break; }
                } catch (Exception e) { log.error(i18n.getMessage("executor.loop.incrementError.evaluation", loopName, e.getMessage())); break; }
            } else current++;

            if (current > max && (loopAction.getConditionExpression() == null || loopAction.getConditionExpression().trim().isEmpty())) {
                if (current > max + 100000 && loopAction.getFrom() != null && Long.parseLong(loopAction.getFrom()) < current ) { // Ajustado el límite de salvaguarda
                    log.warn(i18n.getMessage("executor.loop.infiniteLoopGuard", iteratorVar)); break;
                }
            }
        }
        parentContext.remove(iteratorVar);
    }
}