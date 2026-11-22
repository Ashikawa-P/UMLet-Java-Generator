@echo off
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
javac -encoding UTF-8 -d build\classes src\org\umletgen\UmletJavaGenerator.java
if errorlevel 1 exit /b 1
jar --create --file umlet-java-generator.jar --main-class org.umletgen.UmletJavaGenerator -C build\classes .
echo Erstellt: umlet-java-generator.jar
