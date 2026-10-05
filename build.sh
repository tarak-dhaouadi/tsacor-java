#!/bin/sh
# Builds dist/tsacor-java-<version>.jar with a plain JDK (no Maven, no downloads).
# Usage: ./build.sh [--test]
set -e
VERSION=0.1.1
cd "$(dirname "$0")"
rm -rf build dist
mkdir -p build/classes build/test-classes dist
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -d build/classes src/main/java/org/tsacor/*.java
cp -r src/main/resources/* build/classes/
printf 'Main-Class: org.tsacor.Main\nImplementation-Title: tsacor-java\nImplementation-Version: %s\n' "$VERSION" > build/manifest.txt
jar --create --file "dist/tsacor-java-$VERSION.jar" --manifest build/manifest.txt -C build/classes .
echo "Built dist/tsacor-java-$VERSION.jar"
if [ "$1" = "--test" ]; then
  javac -encoding UTF-8 -cp build/classes -d build/test-classes src/test/java/org/tsacor/SelfTest.java
  java -Djava.awt.headless=true -cp build/classes:build/test-classes org.tsacor.SelfTest
fi
