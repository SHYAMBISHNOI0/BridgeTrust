@echo off
REM BridgeTrust native JavaFX launcher. Requires JDK 21+ and Maven 3.9+.
cd /d "%~dp0"
if not defined BT_DATA_DIR set "BT_DATA_DIR=%CD%\data"
where mvn >nul 2>&1 || (echo Install JDK 21+ and Maven 3.9+. See README.md. & exit /b 1)
call mvn javafx:run %*
