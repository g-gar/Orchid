package com.ggar.orchid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
public class I18nService {
    private static final Logger log = LoggerFactory.getLogger(I18nService.class);
    private final MessageSource messageSource;
    private final Locale configuredLocale;

    @Autowired
    public I18nService(MessageSource messageSource, @Value("${app.locale:}") String localeString) {
        Locale resolvedLocale;
        this.messageSource = messageSource;
        if (StringUtils.hasText(localeString)) {
            try {
                resolvedLocale = StringUtils.parseLocaleString(localeString);
                log.info("Application locale configured to: {}", resolvedLocale);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid 'app.locale' value: '{}'. Using system default locale.", localeString, e);
                resolvedLocale = Locale.getDefault();
            }
        } else {
            resolvedLocale = Locale.getDefault();
            log.info("'app.locale' not specified. Using system default locale: {}", resolvedLocale);
        }
        this.configuredLocale = resolvedLocale;
    }

    public String getMessage(String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, configuredLocale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
    public String getMessage(String code) {
        return getMessage(code, (Object[]) null);
    }
}
