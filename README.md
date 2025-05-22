# Orchid Job Orchestrator 🚀

**Dynamic and Extensible YAML-based Task Orchestrator**

---

## 📜 Description

Orchid is a lightweight and powerful job orchestrator built with Java 17 and Spring Boot. It allows defining complex workflows using YAML configuration files, where each task is broken down into stages and actions. Its modular design facilitates extension with custom Java classes (plugins) per task, without needing to recompile the orchestrator's core.

This project was born from the need to automate sequential and conditional processes declaratively, offering a flexible solution for various use cases.

---

## ✨ Key Features

* **YAML Task Definition:** Describe your workflows intuitively and legibly.
* **Hierarchical Structure:** Organize tasks into `Jobs` > `Stages` > `Actions`.
* **Versatile Action Types:**
    * `spel`: Executes SpEL (Spring Expression Language) expressions for dynamic logic and data manipulation.
    * `loop`: Iterates over numerical ranges or collections from the context.
    * `conditional`: Executes actions based on the result of a SpEL expression.
    * `command`: Executes operating system commands.
    * `javaMethod`: Invokes methods of Spring beans or utility classes.
* **Job Context:** A data map (`jobContext`) shared and modified throughout the execution of a task.
* **Per-Task Plugin System:**
    * Dynamic loading of individual `.jar` and `.class` files located in a `lib/` subdirectory of each task.
    * Allows invoking custom utility classes from SpEL (`T(com.package.MyClass).method()`) without recompiling the orchestrator.
* **Automatic Task Loading:** Scans a `jobs/` directory in the classpath at startup to automatically discover and execute tasks.
* **Initial Parameters per Task:** Define a `parameters.yml` file alongside each `job.yml` to provide initial data to the `jobContext`.
* **Internationalized Logging (i18n):**
    * Log messages externalized in `logs.properties` files.
    * Support for multiple languages (e.g., `logs_es.properties`, `logs_en.properties`).
    * Configurable locale via the `app.locale` property in `application.properties`.
* **Detailed Execution Lineage Tracking:** Clear logs showing the complete path from the root stage to the current action, facilitating debugging.

---

## ⚙️ Configuration and Usage

### 1. Project Structure

The following directory structure is expected within `src/main/resources/`:

```
src/main/resources/
├── jobs/
│   └── {jobName1}/
│       ├── job.yml             # Task definition
│       ├── parameters.yml      # (Optional) Initial parameters for this task
│       └── lib/                # (Optional) Directory for plugins (JARs, .class files)
│           ├── MyClass.class
│           └── myLibrary.jar
│   └── {jobName2}/
│       ├── job.yml
│       └── ...
├── logs.properties             # Default log messages (English)
├── logs_es.properties          # Log messages in Spanish (or other languages)
└── application.properties      # Spring Boot application configuration
```

### 2. Defining a `job.yml`

Each `job.yml` file defines a single task.

**Example (`SieveEratosthenes/job.yml`):**

```yaml
id: "SieveEratosthenes"
description: "Generates a sieve of prime numbers"
initialContextParameters: ["minInteger", "maxInteger"] # Expected parameters from parameters.yml
stages:
  - name: "GeneratePreCondition"
    description: "Precondition: list of odd numbers in a range."
    actions:
      - name: "CreateEmptyList"
        type: "spel"
        expression: "new java.util.ArrayList()" # Creates a mutable list
        returnToContextAs: "list"
      - name: "PopulateNumbersBasedOnCondition"
        type: "loop"
        from: "#jobContext['minInteger']"
        to: "#jobContext['maxInteger']"
        iteratorVariable: "currentNumber"
        conditionExpression: "#currentNumber <= #jobContext['maxInteger']" # Loop condition
        incrementExpression: "#currentNumber + 1"        # How the iterator is incremented
        body: # Actions to execute in each loop iteration
          - name: "AddNumberIfOdd"
            type: "conditional"
            condition: "#currentNumber % 2 == 1" # SpEL condition
            actions: # Actions if the condition is true
              - name: "AddOddNumberToList"
                type: "spel"
                expression: "#jobContext['list'].add(#currentNumber)"
  - name: "FilterPrimesInList" # A stage can also be a single action
    type: "loop" # This stage is a loop
    description: "Filters the list to keep only prime numbers."
    collection: "#jobContext['list']"
    iteratorVariable: "currentPrimeCandidate"
    returnToContextAs: "primeList" # Optional: the action's result (if any) is saved here
    body:
      - name: "CheckAndRemoveIfNotPrime"
        type: "conditional"
        condition: "T(com.ggar.orchid.util.PrimeChecker).isNotPrime(#currentPrimeCandidate)" # Calls a plugin class
        actions:
          - name: "RemoveNonPrime"
            type: "spel"
            expression: "#jobContext['list'].remove(#currentPrimeCandidate)" # Modifies the original list
```

**Key Fields:**

* **`JobDefinition`**:
    * `id`: Unique job identifier.
    * `description`: Readable description.
    * `initialContextParameters`: (Optional) List of parameter names this job expects from its `parameters.yml`.
    * `stages`: List of `StageDefinition`.
* **`StageDefinition`**:
    * `name`: Stage name.
    * `description`: (Optional) Description.
    * Can contain an `actions` list (sub-actions) OR act as a single action by directly defining action properties (`type`, `expression`, etc., using `@JsonUnwrapped`).
* **`Action` (Base)**:
    * `name`: (Optional) Action name.
    * `description`: (Optional) Description.
    * `type`: (Required) Action type (`spel`, `loop`, `conditional`, `command`, `javaMethod`).
    * `returnToContextAs`: (Optional) Key under which the action's result will be saved in the `jobContext`.
* **`Action` Subclasses (specific properties):**
    * `SpelAction`: `expression` (SpEL String).
    * `LoopAction`:
        * `from`, `to`, `incrementExpression` (for numeric loops).
        * `collection` (SpEL expression evaluating to a collection, for collection loops).
        * `iteratorVariable` (variable name for each element/number in the iteration).
        * `conditionExpression` (optional, SpEL condition to continue the loop).
        * `body` (list of `Action` to execute in each iteration).
    * `ConditionalAction`:
        * `condition` (SpEL expression evaluating to boolean).
        * `actions` (list of `Action` to execute if the condition is true; maps to `thenActions` in the POJO).
    * `CommandAction`:
        * `command` (command to execute).
        * `args` (list of arguments for the command, can be SpEL expressions).
        * `captureOutput` (boolean, whether to capture the command's output).
    * `JavaMethodAction`:
        * `beanName` (Spring bean name).
        * `method` (name of the method to invoke).
        * `args` (list of arguments for the method; can be literals or SpEL expressions like `#{jobContext['myVar']}` or `#someLocalVariable`).

### 3. Initial Parameters (`parameters.yml`)

Create a `parameters.yml` file in the same directory as `job.yml` to provide initial values to the `jobContext`.

**Example** (`SieveEratosthenes/parameters.yml`):
```yaml
minInteger: 1
maxInteger: 100
```

### 4. Plugins (Custom Classes/JARs)

To add custom Java logic accessible from SpEL (e.g., `T(com.my.package.MyUtil).myMethod()`):

1.  Create a `lib/` subdirectory within your job's directory (e.g., `src/main/resources/jobs/MyJob/lib/`).
2.  **For `.class` files:**
    * Compile your `.java` file (e.g., `javac -d . MyClassUtil.java` if `MyClassUtil.java` has `package com.my.package;`).
    * Place the resulting `.class` file in the correct package structure within the `lib/` directory.
        * Example: `src/main/resources/jobs/MyJob/lib/com/my/package/MyClassUtil.class`
    * If the class has no `package` (default package), place it directly in `lib/`:
        * Example: `src/main/resources/jobs/MyJob/lib/MyClassWithoutPackage.class`
        * In SpEL: `T(MyClassWithoutPackage).method()`
3.  **For `.jar` files:**
    * Package your classes and dependencies into a JAR file.
    * Place the `.jar` file directly into the `lib/` directory.
        * Example: `src/main/resources/jobs/MyJob/lib/my-utilities.jar`

The orchestrator will automatically load these classes/JARs into an isolated ClassLoader for that job, making them available to SpEL expressions with `T()`.

### 5. Locale Configuration for Logs

You can configure the application's log language in `src/main/resources/application.properties`:

```properties
# Example for Spanish (Spain)
app.locale=es_ES

# Example for English (United States)
# app.locale=en_US
```

Ensure you have corresponding `logs_{locale}.properties` files (e.g., `logs_es.properties`).

## 🚀 Execution

Simply run the Spring Boot application. The `JobAutoLoaderRunner` will scan the `src/main/resources/jobs/` directory, load each `job.yml` and its associated `parameters.yml`, and execute the tasks sequentially.

## 🛠️ Development and Dependencies

* Java 17
* Spring Boot 3.x
* Lombok
* Jackson (for YAML)
* SLF4J with Logback (for logging)

Refer to the `build.gradle` file for detailed dependencies.

## 🔮 Potential Future Enhancements

* Parallel execution of jobs and/or stages.
* REST API to control and monitor jobs.
* Basic user interface.
* More advanced retry mechanisms and error handling per action/stage.
* Schema validation for `job.yml`.
* Support for job state persistence.
* Integration with messaging systems for asynchronous jobs.

---

Thank you for using Orchid!