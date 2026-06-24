#!/bin/sh

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
