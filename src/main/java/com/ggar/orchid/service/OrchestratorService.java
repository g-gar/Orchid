package com.ggar.orchid.service;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private final ActionExecutorFactory actionExecutorFactory;
    private final I18nService i18n;

    @Autowired
    public OrchestratorService(ActionExecutorFactory actionExecutorFactory, I18nService i18n) {
        this.actionExecutorFactory = actionExecutorFactory;
        this.i18n = i18n;
    }

    public Map<String, Object> executeJob(JobDefinition jobDef, Map<String, Object> initialParameters, ClassLoader jobSpecificClassLoader) {
        if (jobDef == null) {
            log.error(i18n.getMessage("orchestrator.jobDefinitionNull"));
            return new ConcurrentHashMap<>();
        }
        Map<String, Object> jobContext = new ConcurrentHashMap<>();
        if (initialParameters != null) jobContext.putAll(initialParameters);

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
            executeActions(stageDef.getSubActions(), jobContext, jobSpecificClassLoader, lineage);
        } else if (stageDef.isSingleActionStage()) {
            Action actionToExecute = stageDef.getActionDefinition();
            if (actionToExecute.getName() == null || actionToExecute.getName().trim().isEmpty()) actionToExecute.setName(stageDef.getName());
            if (actionToExecute.getDescription() == null || actionToExecute.getDescription().trim().isEmpty()) actionToExecute.setDescription(stageDef.getDescription());
            log.debug(i18n.getMessage("orchestrator.stageAsSingleAction", stageName, actionToExecute.getType()));
            executeAction(actionToExecute, jobContext, jobSpecificClassLoader, lineage);
        } else {
            log.warn(i18n.getMessage("orchestrator.stageEmpty", stageName));
        }
    }

    public void executeActions(List<Action> actions, Map<String, Object> context, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        if (actions == null) return;
        for (Action action : actions) {
            executeAction(action, context, jobSpecificClassLoader, lineage);
        }
    }

    private void executeAction(Action action, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        String actionName = Optional.ofNullable(action.getName()).orElse(i18n.getMessage("orchestrator.unnamedAction"));
        log.info(i18n.getMessage("orchestrator.executingActionWithLineage", actionName, action.getType(), lineage.toString()));
        try {
            ActionExecutor executor = actionExecutorFactory.getExecutor(action.getType());
            Object result = executor.execute(action, jobContext, this, jobSpecificClassLoader, lineage);
            if (action.getReturnToContextAs() != null && result != null) {
                log.info(i18n.getMessage("orchestrator.actionResultSaved", actionName, action.getReturnToContextAs()));
                jobContext.put(action.getReturnToContextAs(), result);
            } else if (action.getReturnToContextAs() != null) {
                log.debug(i18n.getMessage("orchestrator.actionResultNull", actionName, action.getReturnToContextAs()));
            }
        } catch (Exception e) {
            log.error(i18n.getMessage("orchestrator.actionExecutionErrorWithLineage", actionName, action.getType(), lineage.toString(), e.getMessage()), e);
        }
    }
}