#!/bin/bash

# Simple script to build and run ClosureJVM

echo "Building ClosureJVM..."

# Prefer gradlew if present, else use system gradle
GRADLE_CMD="./gradlew"
if [ ! -x "$GRADLE_CMD" ]; then
  GRADLE_CMD="gradle"
fi

$GRADLE_CMD build

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo ""
    echo "Usage examples:"
    echo "  Run with default iterations (1000):"
    echo "    java -jar build/libs/closurejvm-0.1.0.jar"
    echo ""
    echo "  Run with custom iterations:"
    echo "    java -jar build/libs/closurejvm-0.1.0.jar 500"
    echo ""
    echo "  Run with custom corpus path:"
    echo "    java -jar build/libs/closurejvm-0.1.0.jar 1000 examples/corpus"
    echo ""
    echo "To test the thread leak example:"
    echo "  java -cp build/classes/java/main examples.ThreadLeakExample leak"
    echo ""
    echo "To test the proper thread management:"
    echo "  java -cp build/classes/java/main examples.ThreadLeakExample"
else
    echo "Build failed!"
    exit 1
fi
