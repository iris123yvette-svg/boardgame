@echo off
chcp 65001 >nul
setlocal
rem ============================================================
rem  One-click launcher for boardgame-dashboard (JDK 17)
rem  Usage:  run.bat           -> use port from application.yml
rem          run.bat 8081      -> force port 8081
rem ============================================================
set "JAVA_HOME=C:\Users\34390\.jdks\temurin-17.0.20.1"
set "JAR=%~dp0target\boardgame-dashboard-1.0.0.jar"
cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 17 not found at %JAVA_HOME%
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo Jar not found, building with Maven...
  call mvn -q package -DskipTests
)

rem 不带参数时不传 --server.port，端口完全由 application.yml 决定
set "PORT_ARG="
set "SHOW_PORT=8080 (from application.yml)"
if not "%~1"=="" (
  set "PORT_ARG=--server.port=%~1"
  set "SHOW_PORT=%~1 (from command line)"
)

echo.
echo  Board Game Dashboard  --  http://localhost:%SHOW_PORT%
echo  Press Ctrl+C to stop.
echo.
"%JAVA_HOME%\bin\java.exe" -jar "%JAR%" %PORT_ARG%
pause
