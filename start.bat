@echo off
setlocal
cd /d "%~dp0"

if not exist "mvnw.cmd" (
    echo ERROR: Maven Wrapper was not found.
    echo Make sure start.bat is in the CampusBite project folder.
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java was not found. Install JDK 17 or newer and add it to PATH.
    pause
    exit /b 1
)

echo Starting CampusBite...
echo The first run may take a few minutes while Maven downloads dependencies.
echo When startup completes, open http://localhost:8080/
echo Keep this window open. Press Ctrl+C here to stop the app.
echo.

call "mvnw.cmd" clean package exec:java
if errorlevel 1 (
    echo.
    echo CampusBite stopped because startup or the build failed.
    pause
    exit /b 1
)

endlocal