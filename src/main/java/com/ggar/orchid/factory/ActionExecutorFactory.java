package com.ggar.orchid.factory;

import com.ggar.orchid.evaluator.SpelExpressionEvaluator;
import com.ggar.orchid.executor.*;
import com.ggar.orchid.service.I18nService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class ActionExecutorFactory {
    private static final Logger log = LoggerFactory.getLogger(ActionExecutorFactory.class);
    private final ApplicationContext applicationContext;
    private final SpelExpressionEvaluator spelEvaluator;
    private final I18nService i18n;

    @Autowired
    public ActionExecutorFactory(ApplicationContext applicationContext, SpelExpressionEvaluator spelEvaluator, I18nService i18n) {
        this.applicationContext = applicationContext;
        this.spelEvaluator = spelEvaluator;
        this.i18n = i18n;
    }

    public ActionExecutor getExecutor(String type) {
        log.debug(i18n.getMessage("factory.gettingExecutor", type));
        switch (type) {
            // Los constructores de ActionExecutor no cambian, pasan el I18nService como antes
            case "spel": return new SpelActionExecutor(spelEvaluator, i18n);
            case "loop": return new LoopActionExecutor(spelEvaluator, i18n);
            case "conditional": return new ConditionalActionExecutor(spelEvaluator, i18n);
            case "command": return new CommandActionExecutor(spelEvaluator, i18n);
            case "javaMethod": return new JavaMethodActionExecutor(applicationContext, spelEvaluator, i18n);
            default:
                log.error(i18n.getMessage("factory.unsupportedActionType", type));
                throw new IllegalArgumentException(i18n.getMessage("factory.unsupportedActionType.runtime", type));
        }
    }
}
