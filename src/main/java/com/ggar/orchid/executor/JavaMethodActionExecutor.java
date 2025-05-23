package com.ggar.orchid.executor;

import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import com.ggar.orchid.model.Action;
import com.ggar.orchid.model.ActionExecutionLineage;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JavaMethodActionExecutor implements ActionExecutor {
    private static final Logger log = LoggerFactory.getLogger(JavaMethodActionExecutor.class);
    private final ApplicationContext applicationContext;
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;

    public JavaMethodActionExecutor(ApplicationContext applicationContext, SpelExpressionEvaluator spelEvaluator, I18nService i18n) {
        this.applicationContext = applicationContext;
        this.spelEvaluator = spelEvaluator;
        this.i18n = i18n;
    }

    @Override
    public Object execute(Action action, Map<String, Object> jobContext, OrchestratorService orchestratorService, ClassLoader jobSpecificClassLoader, ActionExecutionLineage lineage, Map<String, Object> additionalSpelVariables) {
        com.ggar.orchid.model.JavaMethodAction javaMethodAction = (com.ggar.orchid.model.JavaMethodAction) action;
        String targetIdentifier = javaMethodAction.getBeanName();
        String methodName = javaMethodAction.getMethod();

        // CORRECCIÓN: Asegurar que targetIdentifier no sea nulo para construir logTargetName
        if (!StringUtils.hasText(targetIdentifier)) {
            log.error(i18n.getMessage("executor.javamethod.targetIdentifierMissing"));
            throw new IllegalArgumentException(i18n.getMessage("executor.javamethod.targetIdentifierMissing.runtime"));
        }
        String logTargetName = StringUtils.hasText(methodName) ? targetIdentifier + "." + methodName : targetIdentifier + " (constructor only)";
        log.debug(i18n.getMessage("executor.javamethod.executing", logTargetName));

        Object targetInstance = null;
        Class<?> targetClass = null;

        // 1. Try to resolve targetIdentifier as a key in jobContext
        if (jobContext.containsKey(targetIdentifier)) {
            targetInstance = jobContext.get(targetIdentifier);
            if (targetInstance != null) {
                targetClass = targetInstance.getClass();
                log.debug(i18n.getMessage("executor.javamethod.resolvedFromContext", targetIdentifier, targetClass.getName()));
            } else {
                log.warn(i18n.getMessage("executor.javamethod.contextKeyNull", targetIdentifier));
            }
        }

        // 2. If not found or null in context, try to resolve as a Spring bean
        if (targetInstance == null) {
            try {
                targetInstance = applicationContext.getBean(targetIdentifier);
                targetClass = targetInstance.getClass();
                log.debug(i18n.getMessage("executor.javamethod.resolvedAsBean", targetIdentifier));
            } catch (NoSuchBeanDefinitionException nsbe) {
                // 3. If not a bean, try to load and instantiate as a FQCN from jobSpecificClassLoader
                if (jobSpecificClassLoader != null) {
                    log.debug(i18n.getMessage("executor.javamethod.notABeanOrContextKey", targetIdentifier));
                    try {
                        log.debug(i18n.getMessage("executor.javamethod.loadingClass", targetIdentifier, jobSpecificClassLoader));
                        targetClass = jobSpecificClassLoader.loadClass(targetIdentifier);
                        log.debug(i18n.getMessage("executor.javamethod.classLoaded", targetIdentifier));

                        List<Object> constructorArgConfigs = javaMethodAction.getConstructorArgs();
                        Object[] evaluatedConstructorArgs;

                        if (constructorArgConfigs != null && !constructorArgConfigs.isEmpty()) {
                            log.debug(i18n.getMessage("executor.javamethod.evaluatingConstructorArgs", targetIdentifier, constructorArgConfigs));
                            Constructor<?> foundConstructor = findBestMatchingConstructor(targetClass, constructorArgConfigs, jobContext, jobSpecificClassLoader, additionalSpelVariables);
                            if (foundConstructor == null) {
                                String argTypesDesc = constructorArgConfigs.stream().map(Object::toString).collect(Collectors.joining(", "));
                                log.error(i18n.getMessage("executor.javamethod.constructorNotFoundWithArgConfigs", targetIdentifier, constructorArgConfigs.size(), argTypesDesc));
                                throw new NoSuchMethodException(i18n.getMessage("executor.javamethod.constructorNotFoundWithArgs.runtime", targetIdentifier));
                            }
                            Class<?>[] expectedConstructorParamTypes = foundConstructor.getParameterTypes();
                            evaluatedConstructorArgs = new Object[constructorArgConfigs.size()];
                            for (int i = 0; i < constructorArgConfigs.size(); i++) {
                                Object evaluatedYamlArg = spelEvaluator.evaluate(String.valueOf(constructorArgConfigs.get(i)).trim(), jobContext, additionalSpelVariables, jobSpecificClassLoader);
                                evaluatedConstructorArgs[i] = coerceArgument(evaluatedYamlArg, expectedConstructorParamTypes[i], "constructor argument " + i, jobContext, jobSpecificClassLoader, additionalSpelVariables);
                                log.trace(i18n.getMessage("executor.javamethod.constructorArgEvaluated", i, evaluatedConstructorArgs[i]));
                            }
                            foundConstructor.setAccessible(true);
                            targetInstance = foundConstructor.newInstance(evaluatedConstructorArgs);
                        } else {
                            Constructor<?> constructor = targetClass.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            targetInstance = constructor.newInstance();
                        }
                        log.debug(i18n.getMessage("executor.javamethod.classInstantiated", targetIdentifier));
                    } catch (ClassNotFoundException cnfe) {
                        log.error(i18n.getMessage("executor.javamethod.classNotFoundInJobClassLoader", targetIdentifier, jobSpecificClassLoader), cnfe);
                        throw new RuntimeException(i18n.getMessage("executor.javamethod.classNotFoundInJobClassLoader.runtime", targetIdentifier), cnfe);
                    } catch (NoSuchMethodException nsmeForConstructor) {
                        log.error(i18n.getMessage("executor.javamethod.constructorLookupError", targetIdentifier, nsmeForConstructor.getMessage()), nsmeForConstructor);
                        throw new RuntimeException(i18n.getMessage("executor.javamethod.constructorLookupError.runtime", targetIdentifier), nsmeForConstructor);
                    } catch (Exception e) {
                        log.error(i18n.getMessage("executor.javamethod.classInstantiationError", targetIdentifier, e.getMessage()), e);
                        throw new RuntimeException(i18n.getMessage("executor.javamethod.classInstantiationError.runtime", targetIdentifier), e);
                    }
                } else {
                    log.error(i18n.getMessage("executor.javamethod.targetNotFound", targetIdentifier));
                    throw new RuntimeException(i18n.getMessage("executor.javamethod.targetNotFound.runtime", targetIdentifier), nsbe);
                }
            } catch (Exception e) {
                log.error(i18n.getMessage("executor.javamethod.beanResolutionError", targetIdentifier, e.getMessage()), e);
                throw new RuntimeException(i18n.getMessage("executor.javamethod.beanResolutionError.runtime", targetIdentifier), e);
            }
        }

        if (targetInstance == null) {
            log.error(i18n.getMessage("executor.javamethod.targetInstanceNull", targetIdentifier));
            throw new RuntimeException(i18n.getMessage("executor.javamethod.targetInstanceNull.runtime", targetIdentifier));
        }
        if (targetClass == null) targetClass = targetInstance.getClass();

        if (!StringUtils.hasText(methodName)) {
            log.debug(i18n.getMessage("executor.javamethod.noMethodSpecifiedReturningInstance", targetIdentifier));
            return targetInstance;
        }

        List<Object> yamlMethodArgs = javaMethodAction.getArgs();
        Object[] processedMethodArgs;
        Method methodToExecute;

        // Evaluate YAML args first
        int numYamlArgs = (yamlMethodArgs == null) ? 0 : yamlMethodArgs.size();
        Object[] evaluatedYamlArgs = new Object[numYamlArgs];
        if (yamlMethodArgs != null) {
            for (int i = 0; i < numYamlArgs; i++) {
                evaluatedYamlArgs[i] = spelEvaluator.evaluate(String.valueOf(yamlMethodArgs.get(i)).trim(), jobContext, additionalSpelVariables, jobSpecificClassLoader);
            }
        }

        methodToExecute = findBestMatchingMethod(targetClass, methodName, evaluatedYamlArgs, jobContext, jobSpecificClassLoader, additionalSpelVariables);

        if (methodToExecute == null) {
            log.error(i18n.getMessage("executor.javamethod.methodNotFoundDetailed", methodName,
                    numYamlArgs + " args (types based on SpEL evaluation)", targetIdentifier));
            throw new RuntimeException(i18n.getMessage("executor.javamethod.methodNotFound.runtime", methodName));
        }

        Class<?>[] expectedMethodParamTypes = methodToExecute.getParameterTypes();
        processedMethodArgs = new Object[numYamlArgs];
        for (int i = 0; i < numYamlArgs; i++) {
            // Use the already evaluated SpEL result for coercion
            processedMethodArgs[i] = coerceArgument(evaluatedYamlArgs[i], expectedMethodParamTypes[i], "method argument " + i, jobContext, jobSpecificClassLoader, additionalSpelVariables);
            log.trace(i18n.getMessage("executor.javamethod.argProcessed", i, processedMethodArgs[i],
                    (processedMethodArgs[i] != null ? processedMethodArgs[i].getClass().getSimpleName() : "null")));
        }

        try {
            ReflectionUtils.makeAccessible(methodToExecute);
            return ReflectionUtils.invokeMethod(methodToExecute, targetInstance, processedMethodArgs);
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException) ? e.getCause() : e;
            log.error(i18n.getMessage("executor.javamethod.executionError", logTargetName, cause.getMessage()), cause);
            throw new RuntimeException(i18n.getMessage("executor.javamethod.executionError.runtime", logTargetName), cause);
        }
    }

    private Constructor<?> findBestMatchingConstructor(Class<?> targetClass, List<Object> yamlConstructorArgConfigs, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, Map<String, Object> additionalSpelVariables) {
        Object[] evaluatedArgs = new Object[yamlConstructorArgConfigs.size()];
        for (int i = 0; i < yamlConstructorArgConfigs.size(); i++) {
            evaluatedArgs[i] = spelEvaluator.evaluate(String.valueOf(yamlConstructorArgConfigs.get(i)).trim(), jobContext, additionalSpelVariables, jobSpecificClassLoader);
        }
        return findConstructorForEvaluatedArgs(targetClass, Arrays.asList(evaluatedArgs), jobContext, jobSpecificClassLoader, additionalSpelVariables);
    }

    private Object instantiateComplexTypeFromListValues(Class<?> typeToInstantiate, List<?> constructorArgValuesFromSpelList, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, Map<String, Object> additionalSpelVariables)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {

        log.debug(i18n.getMessage("executor.javamethod.instantiatingComplexArg", typeToInstantiate.getName(), constructorArgValuesFromSpelList));
        Constructor<?> constructor = findConstructorForEvaluatedArgs(typeToInstantiate, constructorArgValuesFromSpelList, jobContext, jobSpecificClassLoader, additionalSpelVariables);

        if (constructor == null) {
            log.error(i18n.getMessage("executor.javamethod.constructorNotFoundForArg", ClassUtils.getShortName(typeToInstantiate), constructorArgValuesFromSpelList));
            throw new NoSuchMethodException("No suitable constructor found for " + typeToInstantiate.getName() + " with args " + constructorArgValuesFromSpelList);
        }

        Class<?>[] expectedConstructorParamTypes = constructor.getParameterTypes();
        Object[] finalConstructorArgs = new Object[constructorArgValuesFromSpelList.size()];
        for (int i = 0; i < constructorArgValuesFromSpelList.size(); i++) {
            Object rawListElement = constructorArgValuesFromSpelList.get(i);
            finalConstructorArgs[i] = coerceArgument(rawListElement, expectedConstructorParamTypes[i],
                    "nested constructor argument " + i + " for " + typeToInstantiate.getSimpleName(),
                    jobContext, jobSpecificClassLoader, additionalSpelVariables);
        }

        constructor.setAccessible(true);
        Object instance = constructor.newInstance(finalConstructorArgs);
        log.debug(i18n.getMessage("executor.javamethod.constructorArgSuccess", ClassUtils.getShortName(typeToInstantiate), Arrays.toString(finalConstructorArgs)));
        return instance;
    }

    private Constructor<?> findConstructorForEvaluatedArgs(Class<?> targetClass, List<?> evaluatedArgsList, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, Map<String, Object> additionalSpelVariables) {
        Object[] evaluatedArgs = evaluatedArgsList.toArray();
        Constructor<?>[] candidates = targetClass.getDeclaredConstructors();
        for (Constructor<?> candidate : candidates) {
            if (candidate.getParameterCount() == evaluatedArgs.length) {
                Class<?>[] candidateParamTypes = candidate.getParameterTypes();
                boolean match = true;
                Object[] coercedArgsForThisCandidate = new Object[evaluatedArgs.length]; // To check if coercion is possible
                for (int i = 0; i < evaluatedArgs.length; i++) {
                    coercedArgsForThisCandidate[i] = coerceArgument(evaluatedArgs[i], candidateParamTypes[i], "constructor arg " + i, jobContext, jobSpecificClassLoader, additionalSpelVariables);
                    if (!isAssignable(candidateParamTypes[i], coercedArgsForThisCandidate[i] != null ? coercedArgsForThisCandidate[i].getClass() : null)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    // If a match is found, the actual coercion happens when the caller prepares arguments.
                    // Here we just confirm a match is possible.
                    return candidate;
                }
            }
        }
        return null;
    }

    private Method findBestMatchingMethod(Class<?> targetClass, String methodName, Object[] evaluatedArgs, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, Map<String, Object> additionalSpelVariables) {
        int numArgs = (evaluatedArgs == null) ? 0 : evaluatedArgs.length;
        List<Method> candidateMethods = Arrays.stream(targetClass.getMethods())
                .filter(m -> m.getName().equals(methodName) && m.getParameterCount() == numArgs)
                .collect(Collectors.toList());

        if (candidateMethods.isEmpty()) return null;
        if (candidateMethods.size() == 1 && numArgs == 0) return candidateMethods.get(0); // Simple case: no args, one method

        for (Method candidate : candidateMethods) {
            Class<?>[] candidateParamTypes = candidate.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < numArgs; i++) {
                Object coercedArg = coerceArgument(evaluatedArgs[i], candidateParamTypes[i], "method argument " + i, jobContext, jobSpecificClassLoader, additionalSpelVariables);
                if (!isAssignable(candidateParamTypes[i], coercedArg != null ? coercedArg.getClass() : null)) {
                    match = false;
                    break;
                }
            }
            if (match) return candidate;
        }

        if (!candidateMethods.isEmpty()) {
            log.warn(i18n.getMessage("executor.javamethod.multipleMethodOverloadsOrCoercionFailed", methodName, targetClass.getName(), candidateMethods.size()));
            return candidateMethods.get(0);
        }
        return null;
    }

    // Corrected isAssignable
    private boolean isAssignable(Class<?> targetType, Class<?> sourceType) {
        if (targetType == null) return false; // Cannot assign to a null type
        if (sourceType == null) return !targetType.isPrimitive(); // null can be assigned to any non-primitive reference type

        // Handle primitive vs wrapper assignability
        Class<?> resolvedTargetType = ClassUtils.isPrimitiveWrapper(targetType) ? ClassUtils.resolvePrimitiveClassName(targetType.getName()) : targetType;
        Class<?> resolvedSourceType = ClassUtils.isPrimitiveWrapper(sourceType) ? ClassUtils.resolvePrimitiveClassName(sourceType.getName()) : sourceType;

        if (resolvedTargetType == null) resolvedTargetType = targetType; // If not a wrapper, use original
        if (resolvedSourceType == null) resolvedSourceType = sourceType; // If not a wrapper, use original

        if (resolvedTargetType.isPrimitive() && resolvedSourceType.isPrimitive()) {
            if (resolvedTargetType == resolvedSourceType) return true;
            // Standard widening primitive conversions
            if (resolvedTargetType == double.class && (resolvedSourceType == float.class || resolvedSourceType == long.class || resolvedSourceType == int.class || resolvedSourceType == short.class || resolvedSourceType == byte.class || resolvedSourceType == char.class)) return true;
            if (resolvedTargetType == float.class && (resolvedSourceType == long.class || resolvedSourceType == int.class || resolvedSourceType == short.class || resolvedSourceType == byte.class || resolvedSourceType == char.class)) return true;
            if (resolvedTargetType == long.class && (resolvedSourceType == int.class || resolvedSourceType == short.class || resolvedSourceType == byte.class || resolvedSourceType == char.class)) return true;
            if (resolvedTargetType == int.class && (resolvedSourceType == short.class || resolvedSourceType == byte.class || resolvedSourceType == char.class)) return true;
            if (resolvedTargetType == short.class && (resolvedSourceType == byte.class)) return true;
            return false; // No other implicit primitive widening
        }
        return ClassUtils.isAssignable(targetType, sourceType);
    }

    private Object coerceArgument(Object argValue, Class<?> expectedType, String argContextName, Map<String, Object> jobContext, ClassLoader jobSpecificClassLoader, Map<String, Object> additionalSpelVariables) {
        if (argValue == null) {
            return expectedType.isPrimitive() ? null : null; // Let reflection throw error for null on primitive later if this is null
        }

        // If already assignable, no explicit coercion needed beyond what reflection might do
        if (isAssignable(expectedType, argValue.getClass())) {
            // Special case for Number to specific number types to ensure correct instance for reflection
            if (expectedType == Long.class || expectedType == long.class) {
                if (argValue instanceof Number) return ((Number) argValue).longValue();
            } else if (expectedType == Integer.class || expectedType == int.class) {
                if (argValue instanceof Number) {
                    long longVal = ((Number) argValue).longValue();
                    if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) return (int) longVal;
                }
            } else if (expectedType == Double.class || expectedType == double.class) {
                if (argValue instanceof Number) return ((Number) argValue).doubleValue();
            } else if (expectedType == Float.class || expectedType == float.class) {
                if (argValue instanceof Number) return ((Number) argValue).floatValue();
            }
            // If directly assignable and not one of the specific numeric cases above, return as is
            return argValue;
        }

        log.debug(i18n.getMessage("executor.javamethod.attemptingCoercion", argContextName, argValue.getClass().getSimpleName(), expectedType.getSimpleName()));

        // Specific coercions from Number
        if (argValue instanceof Number numArgValue) {
            if (expectedType == Long.class || expectedType == long.class) {
                log.debug(i18n.getMessage("executor.javamethod.coercingNumberToLong", argValue, expectedType.getSimpleName()));
                return numArgValue.longValue();
            }
            if (expectedType == Integer.class || expectedType == int.class) {
                long longVal = numArgValue.longValue();
                if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                    log.debug(i18n.getMessage("executor.javamethod.coercingNumberToInteger", argValue, expectedType.getSimpleName()));
                    return (int) longVal;
                } else {
                    log.warn(i18n.getMessage("executor.javamethod.coercionNumberToIntegerLoss", argValue, expectedType.getSimpleName()));
                }
            }
            if (expectedType == Double.class || expectedType == double.class) {
                log.debug(i18n.getMessage("executor.javamethod.coercingNumberToDouble", argValue, expectedType.getSimpleName()));
                return numArgValue.doubleValue();
            }
            if (expectedType == Float.class || expectedType == float.class) {
                log.debug(i18n.getMessage("executor.javamethod.coercingNumberToFloat", argValue, expectedType.getSimpleName()));
                return numArgValue.floatValue();
            }
        }

        // Coercions from String
        if (argValue instanceof String strValue) {
            try {
                if (expectedType == Long.class || expectedType == long.class) return Long.parseLong(strValue);
                if (expectedType == Integer.class || expectedType == int.class) return Integer.parseInt(strValue);
                if (expectedType == Double.class || expectedType == double.class) return Double.parseDouble(strValue);
                if (expectedType == Float.class || expectedType == float.class) return Float.parseFloat(strValue);
                if (expectedType == Boolean.class || expectedType == boolean.class) return Boolean.parseBoolean(strValue);
            } catch (NumberFormatException e) {
                log.warn(i18n.getMessage("executor.javamethod.coercionStringToNumberFail", strValue, expectedType.getSimpleName()), e);
            }
        }

        // Recursive instantiation for complex types if argValue is a List from SpEL
        if (argValue instanceof List &&
                !Collection.class.isAssignableFrom(expectedType) &&
                !expectedType.isArray() &&
                !expectedType.isInterface() &&
                !expectedType.isEnum() &&
                !Modifier.isAbstract(expectedType.getModifiers()) &&
                !ClassUtils.isPrimitiveOrWrapper(expectedType) &&
                expectedType != String.class) {
            try {
                log.debug(i18n.getMessage("executor.javamethod.attemptingRecursiveConstructorArg", ClassUtils.getShortName(expectedType), argValue));
                return instantiateComplexTypeFromListValues(expectedType, (List<?>) argValue, jobContext, jobSpecificClassLoader, additionalSpelVariables);
            } catch (Exception e) {
                log.warn(i18n.getMessage("executor.javamethod.recursiveConstructorArgError", ClassUtils.getShortName(expectedType), e.getMessage()), e);
            }
        }

        log.warn(i18n.getMessage("executor.javamethod.coercionSkipped", argValue.getClass().getSimpleName(), expectedType.getSimpleName(), argContextName));
        return argValue;
    }
}