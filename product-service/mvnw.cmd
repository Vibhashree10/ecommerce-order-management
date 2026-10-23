@REM Maven Wrapper for Windows
@REM Licensed to the Apache Software Foundation (ASF)

@echo off
setlocal

for %%i in ("%~dp0.") do set "MAVEN_HOME=%%~fi"

@REM Execute Maven
"%MAVEN_HOME%\bin\mvn.cmd" %*
