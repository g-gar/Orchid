package com.ggar.orchid.executor;

import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;

public class JavaMethodActionExecutor implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(JavaMethodActionExecutor.class);
    private final ApplicationContext applicationContext;
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;
    public JavaMethodActionExecutor(ApplicationContext applicationContext, SpelExpressionEvaluator spelEvaluator, I18nService i18n) {
        this.applicationContext = applicationContext; this.spelEvaluator = spelEvaluator; this.i18n = i18n;
    }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage) {
        com.ggar.orchid.model.JavaMethodAction javaMethodAction = (com.ggar.orchid.model.JavaMethodAction) action;
        String methodNameFull = javaMethodAction.getBeanName() + "." + javaMethodAction.getMethod();
        log.debug(i18n.getMessage("executor.javamethod.executing", methodNameFull)); 
        Object bean;
        try { bean = applicationContext.getBean(javaMethodAction.getBeanName()); }
        catch (Exception e) {
            log.error(i18n.getMessage("executor.javamethod.beanNotFound", javaMethodAction.getBeanName()), e);
            throw new RuntimeException(i18n.getMessage("executor.javamethod.beanNotFound.runtime", javaMethodAction.getBeanName()), e);
        }
        Object[] evaluatedArgs = null; Class<?>[] argTypes = null;
        if (javaMethodAction.getArgs() != null && !javaMethodAction.getArgs().isEmpty()) {
            evaluatedArgs = new Object[javaMethodAction.getArgs().size()]; argTypes = new Class<?>[javaMethodAction.getArgs().size()];
            for (int i = 0; i < javaMethodAction.getArgs().size(); i++) {
                Object argConfig = javaMethodAction.getArgs().get(i);
                if (argConfig instanceof String && ((String) argConfig).matches("^#\\{.*\\}$")) {
                    String expression = ((String) argConfig).substring(2, ((String) argConfig).length() - 1);
                    evaluatedArgs[i] = spelEvaluator.evaluate(expression, jobContext, null, jobSpecificClassLoader);
                } else if (argConfig instanceof String && ((String) argConfig).startsWith("#")) {
                    evaluatedArgs[i] = spelEvaluator.evaluate((String) argConfig, jobContext, null, jobSpecificClassLoader);
                } else evaluatedArgs[i] = argConfig;
                log.trace(i18n.getMessage("executor.javamethod.argEvaluated", i, evaluatedArgs[i]));
                argTypes[i] = (evaluatedArgs[i] != null) ? evaluatedArgs[i].getClass() : Object.class;
            }
        }
        try {
            java.lang.reflect.Method methodToExecute = org.springframework.util.ReflectionUtils.findMethod(bean.getClass(), javaMethodAction.getMethod(), argTypes);
            if (methodToExecute == null) {
                for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                    if (m.getName().equals(javaMethodAction.getMethod()) &&
                            ((javaMethodAction.getArgs() == null && m.getParameterCount() == 0) ||
                                    (javaMethodAction.getArgs() != null && m.getParameterCount() == javaMethodAction.getArgs().size()))) {
                        methodToExecute = m; break;
                    }
                }
            }
            if (methodToExecute == null) {
                log.error(i18n.getMessage("executor.javamethod.methodNotFound", javaMethodAction.getMethod(), javaMethodAction.getBeanName()));
                throw new NoSuchMethodException(i18n.getMessage("executor.javamethod.methodNotFound.runtime", javaMethodAction.getMethod()));
            }
            org.springframework.util.ReflectionUtils.makeAccessible(methodToExecute);
            return org.springframework.util.ReflectionUtils.invokeMethod(methodToExecute, bean, evaluatedArgs);
        } catch (NoSuchMethodException e) {
            log.error(i18n.getMessage("executor.javamethod.methodNotFound.exception", methodNameFull), e);
            throw new RuntimeException(i18n.getMessage("executor.javamethod.methodNotFound.exception.runtime", methodNameFull), e);
        } catch (Exception e) {
            log.error(i18n.getMessage("executor.javamethod.executionError", methodNameFull, e.getMessage()), e);
            throw new RuntimeException(i18n.getMessage("executor.javamethod.executionError.runtime", methodNameFull), e);
        }
    }
}