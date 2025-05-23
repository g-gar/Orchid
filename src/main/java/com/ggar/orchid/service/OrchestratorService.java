package com.ggar.orchid.service;

import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import com.ggar.orchid.executor.ActionExecutor;
import com.ggar.orchid.factory.ActionExecutorFactory;
import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.model.JobDefinition;
import com.ggar.orchid.model.StageDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private final ActionExecutorFactory actionExecutorFactory;
    private final I18nService i18n;
    private final SpelExpressionEvaluator spelEvaluator;
    public static final String PREVIOUS_ACTION_RESULT_KEY = "previousResult"; // Clave para el resultado anterior

    @Autowired
    public OrchestratorService(ActionExecutorFactory actionExecutorFactory, I18nService i18n, SpelExpressionEvaluator spelEvaluator) {
        this.actionExecutorFactory = actionExecutorFactory;
        this.i18n = i18n;
        this.spelEvaluator = spelEvaluator;
    }

    public Map<String, Object> executeJob(JobDefinition jobDef, Map<String, Object> initialParameters, ClassLoader jobSpecificClassLoader) {
        if (jobDef == null) {
            log.error(i18n.getMessage("orchestrator.jobDefinitionNull"));
            return new ConcurrentHashMap<>();
        }
        Map<String, Object> jobContext = new ConcurrentHashMap<>();
        if (initialParameters != null) {
            for (Map.Entry<String, Object> entry : initialParameters.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    jobContext.put(entry.getKey(), entry.getValue());
                } else {
                    if (entry.getKey() == null) {
                        log.warn(i18n.getMessage("orchestrator.skippedNullKeyInInitialParams"));
                    }
                    if (entry.getValue() == null) {
                        log.warn(i18n.getMessage("orchestrator.skippedNullValueInInitialParams", String.valueOf(entry.getKey())));
                    }
                }
            }
        }

        log.info(i18n.getMessage("orchestrator.executingJob",
                Optional.ofNullable(jobDef.getDescription()).orElse(i18n.getMessage("orchestrator.noDescription")),
                jobDef.getId(),
                jobSpecificClassLoader));
        for (StageDefinition stageDef : jobDef.getStages()) {
            String stageName = Optional.ofNullable(stageDef.getName()).orElseGet(() ->
                    stageDef.isSingleActionStage() && stageDef.getActionDefinition().getName() != null ?
                            stageDef.getActionDefinition().getName() : i18n.getMessage("orchestrator.unnamedStage")
            );
            log.info(i18n.getMessage("orchestrator.executingStage", stageName));
            ActionExecutionLineage initialLineage = new ActionExecutionLineage(stageName);
            executeStage(stageDef, jobContext, jobSpecificClassLoader, initialLineage);
        }
        log.info(i18n.getMessage("orchestrator.jobCompleted", jobDef.getId(), jobContext));
        return jobContext;
    }

    private void executeStage(StageDefinition stageDef, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        String stageName = lineage.rootStageName();
        if (stageDef.isSubActionsStage()) {
            log.debug(i18n.getMessage("orchestrator.stageWithSubActions", stageName));
            executeActions(stageDef.getSubActions(), jobContext, jobSpecificClassLoader, lineage, null); // #previousResult es null para la primera acción del stage
        } else if (stageDef.isSingleActionStage()) {
            Action actionToExecute = stageDef.getActionDefinition();
            if (actionToExecute.getName() == null || actionToExecute.getName().trim().isEmpty()) actionToExecute.setName(stageDef.getName());
            if (actionToExecute.getDescription() == null || actionToExecute.getDescription().trim().isEmpty()) actionToExecute.setDescription(stageDef.getDescription());
            log.debug(i18n.getMessage("orchestrator.stageAsSingleAction", stageName, actionToExecute.getType()));
            // Para una acción única en un stage, #previousResult no tendría un contexto previo claro dentro de este stage.
            // Podríamos pasar null o el resultado del stage anterior si tuviéramos esa lógica. Por ahora, null.
            Map<String, Object> additionalSpelVariables = Collections.singletonMap(PREVIOUS_ACTION_RESULT_KEY, null);
            executeAction(actionToExecute, jobContext, jobSpecificClassLoader, lineage, additionalSpelVariables);
        } else {
            log.warn(i18n.getMessage("orchestrator.stageEmpty", stageName));
        }
    }

    // additionalSpelVariablesForFirstAction es el #previousResult de la acción que precedió a esta lista.
    public void executeActions(List<Action> actions, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Object initialPreviousResult) {
        if (actions == null) return;
        Object previousActionResult = initialPreviousResult; // Usar el resultado que vino de "afuera" de esta lista para la primera acción
        for (Action action : actions) {
            Map<String, Object> additionalSpelVariables = new HashMap<>();
            additionalSpelVariables.put(PREVIOUS_ACTION_RESULT_KEY, previousActionResult);

            previousActionResult = executeAction(action, jobContext, jobSpecificClassLoader, lineage, additionalSpelVariables);
        }
    }

    // executeAction ahora devuelve el resultado de la acción (después de unboxing)
    // y acepta additionalSpelVariables
    private Object executeAction(Action action, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariables) {
        String actionName = Optional.ofNullable(action.getName()).orElse(i18n.getMessage("orchestrator.unnamedAction"));
        log.info(i18n.getMessage("orchestrator.executingActionWithLineage", actionName, action.getType(), lineage.toString()));
        Object valueToStoreOrUseInSpel = null;
        try {
            ActionExecutor executor = actionExecutorFactory.getExecutor(action.getType());
            // Pasar additionalSpelVariables al executor
            Object rawActionResult = executor.execute(action, jobContext, this, jobSpecificClassLoader, lineage, additionalSpelVariables);

            valueToStoreOrUseInSpel = rawActionResult;
            if (rawActionResult instanceof Optional) {
                valueToStoreOrUseInSpel = ((Optional<?>) rawActionResult).orElse(null);
                log.debug(i18n.getMessage("orchestrator.unboxedOptionalResult", actionName, valueToStoreOrUseInSpel));
            }

            String returnToContextAsKeyOrSpel = action.getReturnToContextAs();

            if (returnToContextAsKeyOrSpel != null && !returnToContextAsKeyOrSpel.trim().isEmpty()) {
                if (returnToContextAsKeyOrSpel.trim().startsWith("#")) {
                    log.info(i18n.getMessage("orchestrator.evaluatingReturnToContextAsSpel", actionName, returnToContextAsKeyOrSpel));
                    Map<String, Object> spelAssignmentVars = new HashMap<>();
                    // #actionResult se refiere al resultado de la acción actual (ya desenrollado)
                    spelAssignmentVars.put("actionResult", valueToStoreOrUseInSpel);
                    // #previousResult también debería estar disponible si se pasó en additionalSpelVariables
                    if(additionalSpelVariables != null) {
                        spelAssignmentVars.putAll(additionalSpelVariables);
                    }

                    spelEvaluator.evaluate(returnToContextAsKeyOrSpel, jobContext, spelAssignmentVars, jobSpecificClassLoader);
                    log.info(i18n.getMessage("orchestrator.returnToContextAsSpelEvaluated", actionName, returnToContextAsKeyOrSpel));
                } else {
                    if (valueToStoreOrUseInSpel != null) {
                        log.info(i18n.getMessage("orchestrator.actionResultSaved", actionName, returnToContextAsKeyOrSpel));
                        jobContext.put(returnToContextAsKeyOrSpel, valueToStoreOrUseInSpel);
                    } else {
                        log.debug(i18n.getMessage("orchestrator.actionResultNullOrNotSaved", actionName, returnToContextAsKeyOrSpel));
                        // Si es null y la clave existe, ¿deberíamos eliminarla o poner null?
                        // Por ahora, solo se pone si no es null, para evitar NPE en ConcurrentHashMap si se usara putAll.
                        // Pero jobContext.put(key, null) es válido para HashMap/ConcurrentHashMap.
                        // Sin embargo, ConcurrentHashMap NO permite nulls si se usa el constructor con un mapa que los contiene.
                        // Como jobContext aquí ya es un ConcurrentHashMap, put(key, null) es válido.
                        // Vamos a permitir poner nulls explícitamente si returnToContextAs es una clave.
                        jobContext.put(returnToContextAsKeyOrSpel, null);
                    }
                }
            }
        } catch (Exception e) {
            log.error(i18n.getMessage("orchestrator.actionExecutionErrorWithLineage", actionName, action.getType(), lineage.toString(), e.getMessage()), e);
            // El valor de la acción fallida será null para la siguiente acción
            valueToStoreOrUseInSpel = null;
        }
        return valueToStoreOrUseInSpel;
    }
}