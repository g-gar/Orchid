# Orchid Workflow Orchestrator 🚀

**Dynamic, Extensible, and Developer-Friendly YAML-based Task Orchestrator**

---

## 📜 Description

Orchid is a lightweight yet powerful job orchestrator built with Java 17 and Spring Boot. It empowers you to define complex workflows using intuitive YAML configuration files, where each task (job) is broken down into sequential stages, and each stage into a series of actions. Orchid's core philosophy is to provide flexibility and extensibility, allowing for custom Java classes (plugins) to be loaded per job without recompiling the orchestrator.

This project aims to simplify the automation of sequential, conditional, and data-driven processes by offering a declarative approach to workflow management.

---

## ✨ Key Features

* **Declarative YAML Workflows:** Define jobs, stages, and actions in a human-readable YAML format.
* **Hierarchical Structure:** Organize tasks logically: `Jobs` > `Stages` > `Actions`.
* **Versatile Action Types:**
    * `spel`: Execute SpEL (Spring Expression Language) expressions for dynamic logic, data manipulation, and assignments.
    * `loop`: Iterate over numerical ranges or collections from the `jobContext`. Supports modification of the `jobContext` from within the loop body.
    * `conditional`: Execute a sequence of actions based on the boolean outcome of a SpEL expression.
    * `command`: Run operating system commands.
    * `javaMethod`: Highly flexible action to:
        * Instantiate classes (from plugins or classpath) using constructors (with or without arguments).
        * Invoke methods on existing objects in the `jobContext`.
        * Invoke methods on Spring-managed beans.
        * Invoke methods on newly instantiated objects.
* **Dynamic Job Context (`jobContext`):**
    * A `ConcurrentHashMap` shared across all actions within a single job execution.
    * Stores initial parameters, intermediate results, and final outputs.
    * Supports nested parameters from `parameters.yml` which are automatically flattened (e.g., `http.delay` becomes accessible as `jobContext['http.delay']`).
* **Implicit Previous Result (`#previousResult`):**
    * Within a sequence of actions (e.g., under a stage or in a loop/conditional body), the special SpEL variable `#previousResult` holds the unboxed result of the immediately preceding action in that sequence.
* **Flexible Result Handling (`returnToContextAs`):**
    * Assigns the result of an action (after automatic `Optional` unboxing) to a key in `jobContext`.
    * Can be a simple key name (e.g., `myResultKey`).
    * Can be a SpEL expression for more complex assignments or updates to existing objects in `jobContext` (e.g., `#myObject.setProperty(#actionResult)`), where `#actionResult` is the unboxed result of the current action.
* **Per-Job Plugin System:**
    * Dynamically load `.jar` files and individual `.class` files from a `lib/` subdirectory within each job's folder.
    * Loaded classes are available to SpEL's `T()` type operator (e.g., `T(com.myplugin.MyUtil).staticMethod()`) and for instantiation via the `javaMethod` action.
* **Automatic Job Loading & Selective Execution:**
    * Scans a `jobs/` directory in the classpath at startup.
    * Executes all found jobs by default.
    * Supports selective execution of jobs via the `--jobs=jobId1,jobId2` command-line argument (or `--jobs=all`).
* **Initial Parameters per Job:**
    * Define a `parameters.yml` alongside `job.yml` for initial `jobContext` values.
    * Supports nested structures which are flattened into dot-separated keys.
* **Internationalized Logging (i18n):**
    * Log messages are externalized (e.g., `logs.properties`, `logs_es.properties`).
    * Application locale for logs is configurable via `app.locale` in `application.properties`.
* **Detailed Execution Lineage Tracking:** Logs clearly indicate the execution path (Stage > Parent Action > Current Action), aiding debugging.
* **Argument Coercion & Instantiation for `javaMethod`:**
    * Automatic type coercion for common types (e.g., `Integer` to `Long`) for constructor and method arguments.
    * Supports instantiating complex method parameter objects if the YAML argument is a SpEL list representing constructor arguments for that parameter type (e.g., `args: ["#{ {arg1ForParamObject, arg2ForParamObject} }"]`).

---

## ⚙️ Configuration and Usage

### 1. Project Structure

Expected directory structure within `src/main/resources/`:

```
src/main/resources/
├── jobs/
│   └── {yourJobName}/
│       ├── job.yml             # Job definition
│       ├── parameters.yml      # (Optional) Initial parameters for this job
│       └── lib/                # (Optional) Directory for job-specific plugins
│           ├── com/example/MyUtil.class  # Correct package structure for .class files
│           └── my-custom-lib.jar
│   └── ... (other jobs) ...
├── logs.properties             # Default log messages (e.g., English)
├── logs_es.properties          # Spanish log messages (or other locales)
└── application.properties      # Spring Boot application configuration
```

### 2. Defining a `job.yml`

Each `job.yml` defines a single job.

**Example:**

```yaml
id: "ComplexDataProcessing"
description: "A job demonstrating various Orchid features"
initialContextParameters: ["inputPath", "config.retryAttempts"]
stages:
  - name: "Initialization"
    actions:
      - name: "SetupInitialDirs"
        type: "command"
        command: "mkdir"
        args: ["-p", "#jobContext['inputPath'] + '/output'"]
        returnToContextAs: "outputDir"

      - name: "CreateConfigObject" # Using javaMethod for instantiation
        type: "javaMethod"
        beanName: "com.example.jobplugins.MyJobConfig" # FQCN of a class in lib/
        constructorArgs:
          - "#jobContext['config.retryAttempts']" # e.g., an Integer
          - "DEFAULT_MODE"
        # No 'method' means the instance itself is the result
        returnToContextAs: "jobConfigInstance"

  - name: "DataTransformation"
    actions:
      - name: "LoadData"
        type: "javaMethod"
        beanName: "jobConfigInstance" # Using the instance from context
        method: "loadDataFromFile"
        args: ["#jobContext['inputPath'] + '/input.csv'"]
        returnToContextAs: "loadedData" # Might be a List or custom object

      - name: "TransformDataLoop"
        type: "loop"
        collection: "#loadedData" # Uses result of previous action
        iteratorVariable: "currentItem"
        body:
          - name: "ProcessItem"
            type: "spel"
            expression: "#currentItem.toUpperCase() + '_processed'"
            returnToContextAs: "processedItem" # This will be overwritten each iteration
                                              # but #previousResult in next action will see it
          - name: "LogProcessedItem"
            type: "spel"
            expression: "T(org.slf4j.LoggerFactory).getLogger('JobLogger').info('Processed: ' + #previousResult)"
            # No returnToContextAs needed for logging

  - name: "Finalization"
    type: "javaMethod" # Stage as a single action
    beanName: "com.example.jobplugins.ReportingUtil" # Another plugin class
    method: "generateReport"
    args: ["#jobContext"] # Pass the whole context
    returnToContextAs: "reportStatus"
```

**Key Fields:** (Refer to previous README versions for basic field descriptions. Below are highlights of recent additions/clarifications)

* **`Action` (Base)**:
    * `returnToContextAs`:
        * **Simple Key:** `myResult` - Stores the action's result (unboxed from `Optional` if applicable) into `jobContext['myResult']`.
        * **SpEL Expression:** `"#myObjectInContext.setSomeProperty(#actionResult)"` - Executes the SpEL. `#actionResult` is the unboxed result of the current action. `#previousResult` is also available if it's not the first action in a sequence.
* **`JavaMethodAction`**:
    * `beanName`: Can be:
        1.  A key for an object already in `jobContext`.
        2.  The name of a Spring-managed bean.
        3.  A Fully Qualified Class Name (FQCN) of a class from a plugin (or classpath).
    * `constructorArgs`: (Optional) A list of SpEL expressions evaluated to become arguments for the constructor when `beanName` is a FQCN and the action is instantiating it.
    * `method`: (Optional) The name of the method to invoke. If omitted (and `beanName` is a FQCN), the action's result is the newly instantiated object.
    * `args`: A list of SpEL expressions for the method's arguments. If an argument is itself a complex object, you can provide a SpEL list literal `#{ {...} }` whose elements will be used to construct that parameter object.

### 3. Initial Parameters (`parameters.yml`)

Supports nested structures, which are flattened. E.g.:
```yaml
server:
  host: "localhost"
  port: 8080
database:
  url: "jdbc:..."
```
Becomes accessible in `jobContext` as `jobContext['server.host']`, `jobContext['database.url']`, etc.

### 4. Plugins (Custom Classes/JARs)

Place in `jobs/{yourJobName}/lib/`.
* `.class` files: Must follow package structure (e.g., `lib/com/example/MyUtil.class`).
* `.jar` files: Placed directly in `lib/`.

### 5. Selective Job Execution

Run specific jobs from the command line:
`java -jar orchid.jar --jobs=jobId1,anotherJobId`
Use `--jobs=all` or omit `--jobs` to run all discovered jobs.

---

## 🚀 Execution

Build and run the Spring Boot application. The `JobAutoLoaderRunner` will handle job discovery and execution based on command-line arguments or defaults.

---

## 🛠️ Development and Dependencies

* Java 17
* Spring Boot 3.x
* Lombok
* Jackson (for YAML)
* SLF4J with Logback

Refer to `build.gradle` for details.

---

## 🔮 Potential Future Enhancements

(Refer to the "Orchid Next Steps (English, Commented)" document for a detailed list of future ideas.)

---

Thank you for using Orchid!