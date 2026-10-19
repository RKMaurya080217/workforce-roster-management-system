@echo off
setlocal enabledelayedexpansion
title Weekly Roster Management System (WRMS) - Client Release

echo ===============================================================================
echo            WEEKLY ROSTER MANAGEMENT SYSTEM (WRMS) - CLIENT RELEASE
echo ===============================================================================
echo.

:: 1. Check for Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java was not found on your PATH.
    echo [ERROR] Please install Java 17 or higher from https://adoptium.net/ and ensure
    echo         it is added to your system PATH environment variable.
    echo.
    pause
    exit /b 1
)

:: 2. Verify Java 17+
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%g
)
set JAVA_VER=%JAVA_VER:"=%
for /f "delims=." %%v in ("%JAVA_VER%") do set MAJOR_VER=%%v

if %MAJOR_VER% lss 17 (
    echo [ERROR] Java version detected: %JAVA_VER%
    echo [ERROR] Java 17 or higher is required to run the Weekly Roster Management System.
    echo [ERROR] Please download and install JDK 17+ from https://adoptium.net/
    echo.
    pause
    exit /b 1
)

echo [OK] Java runtime verified: Java %JAVA_VER%
echo [INFO] Working directory: %~dp0
echo [INFO] Profile: local (Embedded SQLite Database)
echo.

:: 3. Create required directories
if not exist "%~dp0data" mkdir "%~dp0data"
if not exist "%~dp0logs" mkdir "%~dp0logs"

:: 4. Launch browser asynchronously after 6 seconds
echo [INFO] Starting web application on http://localhost:8080 ...
start /b cmd /c "timeout /t 6 /nobreak >nul & start http://localhost:8080"

:: 5. Launch Application Jar
java -Dspring.profiles.active=local -Dfile.encoding=UTF-8 -jar "%~dp0WRMS-Client-1.0.0.jar"

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Application terminated with an error code: %ERRORLEVEL%
    pause
)
