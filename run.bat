@echo off
rem Construit puis lance FinanceApp (Windows).
rem   run.bat            construit (sans les tests) puis lance
rem   run.bat --test     execute d'abord tous les tests
setlocal
cd /d "%~dp0"

where java >nul 2>nul || (echo [FinanceApp] Java 21+ est requis : https://adoptium.net & exit /b 1)
where mvn >nul 2>nul || (echo [FinanceApp] Maven 3.9+ est requis pour construire le projet. & exit /b 1)

set "TESTS=-DskipTests"
if "%~1"=="--test" set "TESTS="

echo [FinanceApp] Construction...
call mvn -q -B package %TESTS% || exit /b 1

echo [FinanceApp] Lancement...
java %JAVA_OPTS% -jar financeapp-desktop\target\financeapp-desktop.jar
