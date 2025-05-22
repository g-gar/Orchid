package com.ggar.orchid.evaluator;

import com.ggar.orchid.service.I18nService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SpelExpressionEvaluator {
    private static final Logger log = LoggerFactory.getLogger(SpelExpressionEvaluator.class);
    private final SpelExpressionParser spelParser;
    private final I18nService i18n; 

    @Autowired 
    public SpelExpressionEvaluator(I18nService i18n) {
        this.spelParser = new SpelExpressionParser();
        this.i18n = i18n;
    }

    public Object evaluate(String expression, Map<String, Object> contextMap,
                           Map<String, Object> additionalVariables, ClassLoader jobSpecificClassLoader) {
        if (expression == null || expression.trim().isEmpty()) {
            log.trace(i18n.getMessage("spel.evaluator.emptyExpression"));
            return null;
        }
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        evalContext.setVariable("jobContext", contextMap);
        if (contextMap != null) {
            contextMap.forEach((key, value) -> {
                if (!"jobContext".equals(key)) evalContext.setVariable(key, value);
                else log.warn(i18n.getMessage("spel.evaluator.reservedKeyWarning", key));
            });
        }
        if (additionalVariables != null) additionalVariables.forEach(evalContext::setVariable);

        ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (jobSpecificClassLoader != null) {
                Thread.currentThread().setContextClassLoader(jobSpecificClassLoader);
                log.trace(i18n.getMessage("spel.evaluator.tclChanged", jobSpecificClassLoader));
            } else {
                log.trace(i18n.getMessage("spel.evaluator.tclDefault", originalContextClassLoader));
            }
            log.trace(i18n.getMessage("spel.evaluator.evaluating", expression, evalContext.getRootObject(), contextMap));
            Expression expr = spelParser.parseExpression(expression);
            return expr.getValue(evalContext);
        } catch (Exception e) {
            log.error(i18n.getMessage("spel.evaluator.evaluationError", expression, Thread.currentThread().getContextClassLoader(), e.getMessage()), e);
            throw new RuntimeException(i18n.getMessage("spel.evaluator.evaluationError.runtime", expression), e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
            log.trace(i18n.getMessage("spel.evaluator.tclRestored", originalContextClassLoader));
        }
    }

    public <T> T evaluate(String expression, Map<String, Object> contextMap,
                          Map<String, Object> additionalVariables, Class<T> expectedType,
                          ClassLoader jobSpecificClassLoader) {
        Object value = evaluate(expression, contextMap, additionalVariables, jobSpecificClassLoader);
        if (value == null) {
            if (expectedType == Boolean.class) {
                log.trace(i18n.getMessage("spel.evaluator.nullToBooleanFalse", expression));
                return expectedType.cast(Boolean.FALSE);
            }
            log.trace(i18n.getMessage("spel.evaluator.nullForExpectedType", expression, expectedType.getSimpleName()));
            return null;
        }
        if (expectedType.isInstance(value)) return expectedType.cast(value);
        if (expectedType == Boolean.class && value instanceof Number) return expectedType.cast(((Number) value).intValue() != 0);
        if (expectedType == Boolean.class && value instanceof String) return expectedType.cast(Boolean.parseBoolean((String)value));
        if (expectedType == String.class) return expectedType.cast(String.valueOf(value));
        if (Number.class.isAssignableFrom(expectedType)) {
            if (value instanceof Number) {
                if (expectedType == Long.class) return expectedType.cast(((Number) value).longValue());
                if (expectedType == Integer.class) return expectedType.cast(((Number) value).intValue());
                if (expectedType == Double.class) return expectedType.cast(((Number) value).doubleValue());
            } else if (value instanceof String) {
                try {
                    if (expectedType == Long.class) return expectedType.cast(Long.parseLong((String)value));
                    if (expectedType == Integer.class) return expectedType.cast(Integer.parseInt((String)value));
                    if (expectedType == Double.class) return expectedType.cast(Double.parseDouble((String)value));
                } catch (NumberFormatException nfe) {
                    log.error(i18n.getMessage("spel.evaluator.stringToNumberConversionError", value, expectedType.getSimpleName(), nfe.getMessage()));
                    throw new ClassCastException(i18n.getMessage("spel.evaluator.stringToNumberConversionError.runtime", value, expectedType.getName(), nfe.getMessage()));
                }
            }
        }
        log.error(i18n.getMessage("spel.evaluator.typeConversionError", expression, value.getClass().getName(), expectedType.getName()));
        throw new ClassCastException(i18n.getMessage("spel.evaluator.typeConversionError.runtime", expression, value.getClass().getName(), expectedType.getName()));
    }
}