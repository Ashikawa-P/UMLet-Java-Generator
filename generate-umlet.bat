@echo off
setlocal
if "%~1"=="" (
  echo Verwendung: generate-umlet.bat ^<Java-Quellordner^> [Diagrammname]
  exit /b 1
)
java -jar "%~dp0umlet-java-generator.jar" %*
