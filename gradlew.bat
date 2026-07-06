@echo off
setlocal

set "GRADLE_VERSION=9.2.0"
if defined GRADLE_USER_HOME (
  set "GRADLE_USER_HOME_DIR=%GRADLE_USER_HOME%"
) else (
  set "GRADLE_USER_HOME_DIR=%USERPROFILE%\.gradle"
)

set "DIST_DIR=%GRADLE_USER_HOME_DIR%\wrapper\dists\stackwise-gradle-%GRADLE_VERSION%"
set "GRADLE_HOME=%DIST_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
set "ZIP_FILE=%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_BIN%" goto run_gradle

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if not exist "%ZIP_FILE%" (
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
  if errorlevel 1 exit /b %errorlevel%
)

if not exist "%GRADLE_HOME%" (
  echo Extracting Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%DIST_DIR%' -Force"
  if errorlevel 1 exit /b %errorlevel%
)

:run_gradle
call "%GRADLE_BIN%" %*
exit /b %errorlevel%
