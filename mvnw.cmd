@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "MVN_CMD=D:\program2\apache-maven-3.9.0\bin\mvn.cmd"
if not exist "%MVN_CMD%" (
  set "MVN_CMD=mvn.cmd"
)
if exist "%SCRIPT_DIR%.mvn\settings.xml" (
  call "%MVN_CMD%" -s "%SCRIPT_DIR%.mvn\settings.xml" %*
) else (
  call "%MVN_CMD%" %*
)
exit /b %ERRORLEVEL%
