@echo off
rem Builds dist\tsacor-java-0.1.1.jar with a plain JDK (no Maven, no downloads).
setlocal
set VERSION=0.1.1
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes build\test-classes dist
dir /s /b src\main\java\org\tsacor\*.java > build\sources.txt
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -d build\classes @build\sources.txt || exit /b 1
xcopy /s /y /q src\main\resources\* build\classes\ >nul
(echo Main-Class: org.tsacor.Main& echo Implementation-Title: tsacor-java& echo Implementation-Version: %VERSION%) > build\manifest.txt
jar --create --file dist\tsacor-java-%VERSION%.jar --manifest build\manifest.txt -C build\classes . || exit /b 1
echo Built dist\tsacor-java-%VERSION%.jar
if "%1"=="--test" (
  javac -encoding UTF-8 -cp build\classes -d build\test-classes src\test\java\org\tsacor\SelfTest.java || exit /b 1
  java -Djava.awt.headless=true -cp build\classes;build\test-classes org.tsacor.SelfTest
)
