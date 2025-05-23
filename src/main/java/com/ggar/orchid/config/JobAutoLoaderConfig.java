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
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

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

    private Set<String> parseJobsToRunArgument(String[] cliArgs) {
        for (String arg : cliArgs) {
            if (arg.startsWith("--jobs=")) {
                String value = arg.substring("--jobs=".length());
                if (!StringUtils.hasText(value)) {
                    log.warn(i18n.getMessage("job.autoloader.emptyJobsArgument"));
                    return Collections.emptySet(); // No ejecutar ninguno si --jobs= está vacío
                }
                if ("all".equalsIgnoreCase(value.trim())) {
                    return null; // Indica ejecutar todos
                }
                return new HashSet<>(Arrays.asList(value.split(",")));
            }
        }
        return null; // No se encontró el argumento --jobs, ejecutar todos por defecto
    }

    @Bean
    public CommandLineRunner jobAutoLoadRunner(OrchestratorService orchestratorService) {
        return args -> {
            log.info(i18n.getMessage("job.autoloader.starting"));

            Set<String> jobsToRun = parseJobsToRunArgument(args);
            if (jobsToRun != null && jobsToRun.isEmpty() && Arrays.stream(args).anyMatch(a -> a.startsWith("--jobs="))) {
                // Esto cubre el caso donde --jobs= fue provisto pero sin valor.
                log.info(i18n.getMessage("job.autoloader.noJobsSpecifiedToRun"));
                log.info(i18n.getMessage("job.autoloader.finished"));
                return;
            }
            if (jobsToRun != null) {
                log.info(i18n.getMessage("job.autoloader.specificJobsRequested", String.join(", ", jobsToRun)));
            } else {
                log.info(i18n.getMessage("job.autoloader.runningAllJobs"));
            }


            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
            try {
                Resource[] jobResources = resolver.getResources("classpath*:jobs/**/job.yml");
                if (jobResources.length == 0) {
                    log.warn(i18n.getMessage("job.autoloader.noJobFilesFound")); return;
                }

                boolean anyJobExecuted = false;
                for (Resource jobResource : jobResources) {
                    String jobResourcePath = "N/A";
                    try {
                        jobResourcePath = jobResource.getURL().getPath();
                    } catch (IOException e) {
                        log.warn(i18n.getMessage("job.autoloader.cannotGetJobResourcePath", jobResource.getDescription()));
                    }
                    log.info(i18n.getMessage("job.autoloader.processingJobFile", jobResourcePath));

                    JobDefinition jobDefinition;
                    try (InputStream jobInputStream = jobResource.getInputStream()) {
                        jobDefinition = yamlMapper.readValue(jobInputStream, JobDefinition.class);
                    } catch (Exception e) {
                        log.error(i18n.getMessage("job.autoloader.errorParsingJobFile", jobResourcePath, e.getMessage()), e); continue;
                    }

                    // FILTRADO DE JOBS
                    if (jobsToRun != null && !jobsToRun.contains(jobDefinition.getId())) {
                        log.info(i18n.getMessage("job.autoloader.skippingJobNotRequested", jobDefinition.getId()));
                        continue; // Saltar este job si no está en la lista de jobs a ejecutar
                    }

                    anyJobExecuted = true;
                    Map<String, Object> initialParameters = loadAndFlattenInitialParameters(jobResource, jobDefinition);
                    ClassLoader jobSpecificClassLoader = createJobSpecificClassLoader(jobResource, jobDefinition.getId());

                    if (jobDefinition.getInitialContextParameters() != null) {
                        for (String requiredParam : jobDefinition.getInitialContextParameters()) {
                            if (!initialParameters.containsKey(requiredParam)) {
                                log.warn(i18n.getMessage("job.autoloader.missingRequiredParameter", jobDefinition.getId(), requiredParam));
                            }
                        }
                    }
                    log.info(i18n.getMessage("job.autoloader.executingJobWithLoader",
                            Optional.ofNullable(jobDefinition.getDescription()).orElse(i18n.getMessage("job.autoloader.noDescription")),
                            jobDefinition.getId(),
                            jobSpecificClassLoader.toString()));
                    try {
                        orchestratorService.executeJob(jobDefinition, initialParameters, jobSpecificClassLoader);
                        log.info(i18n.getMessage("job.autoloader.jobCompletedSuccessfully", jobDefinition.getId()));
                    } catch (Exception e) {
                        log.error(i18n.getMessage("job.autoloader.errorDuringJobExecution", jobDefinition.getId(), e.getMessage()), e);
                    }
                    log.info(i18n.getMessage("job.autoloader.jobSeparator"));
                }
                if (jobsToRun != null && !jobsToRun.isEmpty() && !anyJobExecuted) {
                    log.warn(i18n.getMessage("job.autoloader.noMatchingJobsFound", String.join(", ", jobsToRun)));
                }

            } catch (FileNotFoundException e) {
                log.warn(i18n.getMessage("job.autoloader.baseDirNotFound", e.getMessage()));
            } catch (IOException e) {
                log.error(i18n.getMessage("job.autoloader.errorScanningJobDirs", e.getMessage()), e);
            }
            log.info(i18n.getMessage("job.autoloader.finished"));
        };
    }

    private Map<String, Object> loadAndFlattenInitialParameters(Resource jobResource, JobDefinition jobDefinition) {
        Map<String, Object> rawParameters = new HashMap<>();
        String parametersResourcePath = "N/A";
        try {
            Resource parametersResource = jobResource.createRelative("parameters.yml");
            if (parametersResource.exists() && parametersResource.isReadable()) {
                parametersResourcePath = parametersResource.getURL().getPath();
                try (InputStream paramsInputStream = parametersResource.getInputStream()) {
                    Map<String, Object> nestedParameters = yamlMapper.readValue(paramsInputStream, new TypeReference<Map<String, Object>>() {});
                    rawParameters.putAll(nestedParameters);
                    log.info(i18n.getMessage("job.autoloader.parametersLoaded", jobDefinition.getId(), parametersResourcePath));
                } catch (Exception e) {
                    log.warn(i18n.getMessage("job.autoloader.errorParsingParametersFile", jobDefinition.getId(), parametersResourcePath, e.getMessage()), e);
                }
            } else {
                log.info(i18n.getMessage("job.autoloader.parametersFileNotFound", jobDefinition.getId()));
            }
        } catch (IOException e) {
            log.warn(i18n.getMessage("job.autoloader.errorAccessingParametersFile", jobDefinition.getId(), e.getMessage()));
        }

        Map<String, Object> flattenedParameters = new LinkedHashMap<>();
        flattenMap("", rawParameters, flattenedParameters);
        if (!rawParameters.isEmpty() && flattenedParameters.isEmpty() && rawParameters.values().stream().noneMatch(Map.class::isInstance)) {
            log.debug("Parameters for job {} were not nested or flattening resulted in empty map. Using raw parameters.", jobDefinition.getId());
            return rawParameters;
        } else if (!flattenedParameters.isEmpty()){
            log.debug(i18n.getMessage("job.autoloader.parametersFlattened", jobDefinition.getId(), flattenedParameters));
        }
        return flattenedParameters;
    }

    private void flattenMap(String prefix, Map<String, Object> nestedMap, Map<String, Object> flatMap) {
        for (Map.Entry<String, Object> entry : nestedMap.entrySet()) {
            String newPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap = (Map<String, Object>) entry.getValue();
                flattenMap(newPrefix, subMap, flatMap);
            } else {
                flatMap.put(newPrefix, entry.getValue());
            }
        }
    }

    private ClassLoader createJobSpecificClassLoader(Resource jobResource, String jobId) {
        List<URL> pluginUrls = new ArrayList<>();
        String jobResourceDescription = jobResource.getDescription();
        log.debug(i18n.getMessage("job.classloader.creatingForJob", jobId, jobResourceDescription));
        try {
            File jobFile = null;
            URL jobUrl = jobResource.getURL();
            if ("file".equals(jobUrl.getProtocol())) {
                jobFile = new File(jobUrl.toURI());
            } else if ("jar".equals(jobUrl.getProtocol())) {
                log.warn(i18n.getMessage("job.classloader.libDiscoveryInJarLimited", jobResourceDescription));
            } else {
                log.warn(i18n.getMessage("job.classloader.unsupportedResourceProtocol", jobUrl.getProtocol(), jobResourceDescription));
            }
            if (jobFile != null && jobFile.exists()) {
                File jobDir = jobFile.getParentFile();
                if (jobDir != null && jobDir.isDirectory()) {
                    File libDir = new File(jobDir, JOB_LIBS_DIR_NAME);
                    if (libDir.exists() && libDir.isDirectory()) {
                        log.info(i18n.getMessage("job.classloader.searchingPluginsInDir", jobId, libDir.getAbsolutePath()));
                        pluginUrls.add(libDir.toURI().toURL());
                        log.debug(i18n.getMessage("job.classloader.addedDirToClasspath", jobId, libDir.toURI().toURL()));
                        File[] jarFiles = libDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                        if (jarFiles != null) {
                            for (File jarFileEntry : jarFiles) {
                                if (jarFileEntry.isFile()) {
                                    pluginUrls.add(jarFileEntry.toURI().toURL());
                                    log.debug(i18n.getMessage("job.classloader.addedJarToClasspath", jobId, jarFileEntry.toURI().toURL()));
                                }
                            }
                        }
                    } else {
                        log.debug(i18n.getMessage("job.classloader.libDirNotFoundForJob", jobId, libDir.getAbsolutePath()));
                    }
                } else {
                    log.warn(i18n.getMessage("job.classloader.cannotGetParentDir", jobFile.getAbsolutePath()));
                }
            } else if (!"jar".equals(jobUrl.getProtocol())) {
                log.warn(i18n.getMessage("job.classloader.jobFileNotAccessible", jobResourceDescription));
            }
        } catch (Exception e) {
            log.error(i18n.getMessage("job.classloader.errorAccessingLibDir", jobId, jobResourceDescription, e.getMessage()), e);
        }
        ClassLoader parentClassLoader = getClass().getClassLoader();
        log.debug(i18n.getMessage("job.classloader.parentClassLoader", jobId, parentClassLoader.toString()));
        if (!pluginUrls.isEmpty()) {
            URL[] urls = pluginUrls.toArray(new URL[0]);
            log.info(i18n.getMessage("job.classloader.creatingUrlClassLoader", jobId, Arrays.toString(urls)));
            URLClassLoader jobClassLoader = new URLClassLoader(urls, parentClassLoader);
            log.info(i18n.getMessage("job.classloader.createdSuccessfully", jobId, jobClassLoader.toString(), Arrays.toString(jobClassLoader.getURLs())));
            return jobClassLoader;
        } else {
            log.info(i18n.getMessage("job.classloader.noPluginsFound", jobId, parentClassLoader.toString()));
            return parentClassLoader;
        }
    }
}