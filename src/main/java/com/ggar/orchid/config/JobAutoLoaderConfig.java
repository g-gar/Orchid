package com.ggar.orchid.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ggar.orchid.model.JobDefinition;
import com.ggar.orchid.service.I18nService;
import com.ggar.orchid.service.OrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class JobAutoLoaderConfig {

    private static final Logger log = LoggerFactory.getLogger(JobAutoLoaderConfig.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final String JOB_LIBS_DIR_NAME = "lib";

    private final I18nService i18n; 

    @Autowired 
    public JobAutoLoaderConfig(I18nService i18n) {
        this.i18n = i18n;
    }

    @Bean
    public CommandLineRunner jobAutoLoadRunner(OrchestratorService orchestratorService) {
        return args -> {
            log.info(i18n.getMessage("job.autoloader.starting"));

            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
            try {
                Resource[] jobResources = resolver.getResources("classpath*:jobs/**/job.yml");

                if (jobResources.length == 0) {
                    log.warn(i18n.getMessage("job.autoloader.noJobFilesFound"));
                    return;
                }

                for (Resource jobResource : jobResources) {
                    String jobResourcePath = jobResource.getURL().getPath();
                    log.info(i18n.getMessage("job.autoloader.processingJobFile", jobResourcePath));

                    JobDefinition jobDefinition;
                    try (InputStream jobInputStream = jobResource.getInputStream()) {
                        jobDefinition = yamlMapper.readValue(jobInputStream, JobDefinition.class);
                    } catch (Exception e) {
                        log.error(i18n.getMessage("job.autoloader.errorParsingJobFile", jobResourcePath, e.getMessage()), e);
                        continue;
                    }

                    Map<String, Object> initialParameters = loadInitialParameters(jobResource, jobDefinition);
                    ClassLoader jobSpecificClassLoader = createJobSpecificClassLoader(jobResource);

                    if (jobDefinition.getInitialContextParameters() != null) {
                        for (String requiredParam : jobDefinition.getInitialContextParameters()) {
                            if (!initialParameters.containsKey(requiredParam)) {
                                log.warn(i18n.getMessage("job.autoloader.missingRequiredParameter", jobDefinition.getId(), requiredParam));
                            }
                        }
                    }

                    log.info(i18n.getMessage("job.autoloader.executingJob", jobDefinition.getDescription(), jobDefinition.getId()));
                    try {
                        orchestratorService.executeJob(jobDefinition, initialParameters, jobSpecificClassLoader);
                        log.info(i18n.getMessage("job.autoloader.jobCompletedSuccessfully", jobDefinition.getId()));
                    } catch (Exception e) {
                        log.error(i18n.getMessage("job.autoloader.errorDuringJobExecution", jobDefinition.getId(), e.getMessage()), e);
                    }
                    log.info(i18n.getMessage("job.autoloader.jobSeparator"));
                }
            } catch (FileNotFoundException e) {
                log.warn(i18n.getMessage("job.autoloader.baseDirNotFound", e.getMessage()));
            } catch (IOException e) {
                log.error(i18n.getMessage("job.autoloader.errorScanningJobDirs", e.getMessage()), e);
            }
            log.info(i18n.getMessage("job.autoloader.finished"));
        };
    }

    private Map<String, Object> loadInitialParameters(Resource jobResource, JobDefinition jobDefinition) {
        Map<String, Object> initialParameters = new HashMap<>();
        try {
            Resource parametersResource = jobResource.createRelative("parameters.yml");
            if (parametersResource.exists() && parametersResource.isReadable()) {
                try (InputStream paramsInputStream = parametersResource.getInputStream()) {
                    initialParameters = yamlMapper.readValue(paramsInputStream, new TypeReference<Map<String, Object>>() {});
                    log.info(i18n.getMessage("job.autoloader.parametersLoaded", jobDefinition.getId(), parametersResource.getURL().getPath()));
                } catch (Exception e) {
                    log.warn(i18n.getMessage("job.autoloader.errorParsingParametersFile", jobDefinition.getId(), parametersResource.getURL().getPath(), e.getMessage()), e);
                }
            } else {
                log.info(i18n.getMessage("job.autoloader.parametersFileNotFound", jobDefinition.getId()));
            }
        } catch (IOException e) {
            log.warn(i18n.getMessage("job.autoloader.errorAccessingParametersFile", jobDefinition.getId(), e.getMessage()));
        }
        return initialParameters;
    }

    private ClassLoader createJobSpecificClassLoader(Resource jobResource) {
        List<URL> pluginUrls = new ArrayList<>();
        try {
            File jobFile = jobResource.getFile();
            File jobDir = jobFile.getParentFile();
            File libDir = new File(jobDir, JOB_LIBS_DIR_NAME);

            if (libDir.exists() && libDir.isDirectory()) {
                log.debug(i18n.getMessage("job.classloader.searchingPlugins", libDir.getAbsolutePath()));
                pluginUrls.add(libDir.toURI().toURL());
                log.debug(i18n.getMessage("job.classloader.addedToClasspath", libDir.toURI().toURL()));

                File[] jarFiles = libDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (jarFiles != null) {
                    for (File jarFile : jarFiles) {
                        pluginUrls.add(jarFile.toURI().toURL());
                        log.debug(i18n.getMessage("job.classloader.jarAddedToClasspath", jarFile.toURI().toURL()));
                    }
                }
            }
        } catch (IOException e) {
            log.error(i18n.getMessage("job.classloader.errorAccessingLibDir", jobResource.getFilename(), e.getMessage()), e);
        }

        if (!pluginUrls.isEmpty()) {
            return new URLClassLoader(pluginUrls.toArray(new URL[0]), getClass().getClassLoader());
        }
        return getClass().getClassLoader();
    }
}