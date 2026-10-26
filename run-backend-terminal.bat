@echo off
title Waypoint Spring Boot Backend
if "%JAVA_HOME%"=="" (
    if exist "%USERPROFILE%\.jdks\ms-21.0.12.1\bin\java.exe" (
        set "JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.12.1"
    ) else if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
    ) else if exist "C:\Users\ultim\.jdks\liberica-26.0.2.1" (
        set "JAVA_HOME=C:\Users\ultim\.jdks\liberica-26.0.2.1"
    )
)
if defined JAVA_HOME set "Path=%JAVA_HOME%\bin;%Path%"

rem Secrets are never stored in this tracked file. Set them as environment
rem variables, or put KEY=VALUE lines in the gitignored .run\backend-secrets.env.
set "SECRETS_FILE=%~dp0.run\backend-secrets.env"
if exist "%SECRETS_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%SECRETS_FILE%") do set "%%A=%%B"
)
set "MISSING_SECRETS="
for %%V in (JWT_SECRET MONGODB_URI OPENWEATHER_API_KEY R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY) do (
    if not defined %%V call set "MISSING_SECRETS=%%MISSING_SECRETS%% %%V"
)
if defined MISSING_SECRETS (
    echo ERROR: Missing required secrets:%MISSING_SECRETS%
    echo Set them as environment variables or add them to .run\backend-secrets.env
    pause
    exit /b 1
)

set "MONGODB_DATABASE=waypoint"
set "MONGODB_AUTO_INDEX_CREATION=false"
set "DATABASE_INITIALIZER_ENABLED=false"
set "RESUME_STORAGE_MIGRATION_ENABLED=false"
set "FILE_STORAGE_MIGRATION_ENABLED=false"
set "OBJECT_STORAGE_MIGRATION_ENABLED=true"
set "EVENT_LOCATION_MIGRATION_ENABLED=false"
set "JOB_LOCATION_MIGRATION_ENABLED=false"
set "MYHR_DEV_PROVISIONING_ENABLED=false"
set "R2_ENABLED=true"
set "R2_BUCKET_NAME=waypoint-myhr-documents"
set "R2_ACCOUNT_ID=13ac14116122f4897c62acddf1c73b0f"
set "R2_ENDPOINT=https://13ac14116122f4897c62acddf1c73b0f.r2.cloudflarestorage.com/"
set "MYHR_DEV_CREDENTIALS_ENABLED=true"
set "MYHR_DEV_CREDENTIALS_OPEN_TERMINAL=true"
rem Organization registration (Create Organization Account) is blocked unless
rem credential delivery is enabled. Mail is on (MAIL_ENABLED defaults to true);
rem put MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD in .run\backend-secrets.env.
rem The OTP is also printed here as a local fallback.
set "ORGANIZATION_REGISTRATION_DEV_CREDENTIALS_ENABLED=true"
set "ORGANIZATION_REGISTRATION_DEV_OTP_ENABLED=true"
cd /d "%~dp0\backend"
echo ==========================================================
echo Starting Waypoint Spring Boot Backend (Port 8081)
echo Development credential logging is ENABLED
echo Temporary passwords will print directly to this terminal.
echo ==========================================================
.\mvnw.cmd spring-boot:run
pause
