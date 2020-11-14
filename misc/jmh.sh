#!/bin/bash

set -euo pipefail

if [ ! -f "pom.xml" ]; then
  cd ..
  if [ ! -f "pom.xml" ]; then
    echo "Run jmh.sh from project or misc directory"
    exit 1
  fi
fi

JAVAH=${JAVAH:-/usr/lib/jvm/java-8-openjdk-amd64}
JAVA=$JAVAH/bin/java

echo "Using ${JAVA} ($($JAVA -version 2>&1 | head -n 1)) for tests"

if [ "$#" -eq 0 ]; then
  ARGS="dk.casa.streamliner.jmh.Test.*"
else
  ARGS="$@"
fi

if [[ $ARGS != *"-h"* ]]; then
  mvn compile
  env JAVA_HOME=$JAVAH mvn exec:java -Dexec.mainClass=dk.casa.streamliner.asm.TransformASM
fi

CP=$(mvn -q exec:exec -Dexec.executable=echo -Dexec.args="%classpath" 2> /dev/null)

exec $JAVA -Dfile.encoding=UTF-8 -classpath out/asm/:$CP org.openjdk.jmh.Main -rf JSON -rff misc/out.json $ARGS

