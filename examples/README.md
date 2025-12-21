# Examples for ClosureJVM

This directory contains example targets and test cases for the ClosureJVM project.

## ThreadLeakExample.java

This example demonstrates thread leak detection capabilities:

### Usage

After building with Gradle (`gradle build`), run the example:

To run with proper thread management:
```bash
java -cp ../build/classes/java/main examples.ThreadLeakExample
```

To run with thread leaks (to test detection):
```bash
java -cp ../build/classes/java/main examples.ThreadLeakExample leak
```

### What it demonstrates

- Proper thread management (ExecutorService shutdown)
- Thread leak scenario (not shutting down ExecutorService)
- Basic leak detection principles
