@echo off
title Weekly Roster Management System (WRMS)
echo ===============================================================================
echo Starting Weekly Roster Management System (WRMS) in Local Mode (SQLite)
echo ===============================================================================
echo Database File  : data\wrms.db (auto-created if missing)
echo Application URL: http://localhost:8080/
echo Swagger UI     : http://localhost:8080/swagger-ui.html
echo Default Logins : Admin: admin / Admin@123  ^|  Employee: emp001 / password123
echo ===============================================================================

if exist "target\weekly-roster-management-system-1.0.0.jar" (
    java -Duser.timezone=Asia/Kolkata -jar target\weekly-roster-management-system-1.0.0.jar
) else if exist "WRMS.jar" (
    java -Duser.timezone=Asia/Kolkata -jar WRMS.jar
) else (
    echo [INFO] Standalone JAR not found. Launching via Maven spring-boot:run...
    mvn spring-boot:run
)

pause
