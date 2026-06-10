#!/bin/sh
#
# Gradle start up script for UN*X
#
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"

DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
GRADLE_OPTS="${GRADLE_OPTS:-}"

set -e

if [ -n "${JAVA_HOME}" ]; then
  JAVACMD="${JAVA_HOME}/bin/java"
else
  JAVACMD="java"
fi

CLASSPATH="${APP_HOME}/gradle/wrapper/gradle-wrapper.jar"

exec "${JAVACMD}" \
  ${DEFAULT_JVM_OPTS} \
  ${GRADLE_OPTS} \
  ${JAVA_OPTS} \
  -classpath "${CLASSPATH}" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
