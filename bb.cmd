@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ==========================================================================
rem  Better Battle - one-click launcher for Windows
rem    Double-click        start everything and open the site
rem    bb start            same
rem    bb stop             stop everything (data is kept)
rem    bb restart          restart the app (after editing .env)
rem    bb logs             follow the app's logs (Ctrl+C to exit)
rem    bb status           show what's running
rem    bb seed [guild]     add a guild to the ranking (default: Aspiring)
rem    bb snapshot         record today's ranks now
rem    bb update           rebuild after downloading new code
rem    bb backup           save a compressed database dump (keeps 14)
rem    bb restore <file>   replace the database from a dump (asks first)
rem    bb backup-daily     schedule a backup every day at 04:00
rem    bb backup-daily off remove the daily backup schedule
rem    bb reset            DELETE all data and start fresh
rem ==========================================================================
cd /d "%~dp0"
set "URL=http://localhost:4000"
set "CMD=%~1"
if "%CMD%"=="" set "CMD=start"

if /i "%CMD%"=="backup-daily" goto :backup_daily

call :check_docker || goto :fail
call :check_env || goto :fail

if /i "%CMD%"=="start"    goto :start
if /i "%CMD%"=="stop"     ( docker compose stop & goto :done )
if /i "%CMD%"=="restart"  ( docker compose up -d --force-recreate app & goto :done )
if /i "%CMD%"=="logs"     ( docker compose logs -f --tail 100 app & goto :done )
if /i "%CMD%"=="log"      ( docker compose logs -f --tail 100 app & goto :done )
if /i "%CMD%"=="status"   ( docker compose ps & goto :done )
if /i "%CMD%"=="seed"     goto :seed
if /i "%CMD%"=="snapshot" ( docker compose exec app npm run snapshot & goto :done )
if /i "%CMD%"=="update"   ( docker compose up -d --build & goto :done )
if /i "%CMD%"=="backup"   goto :backup
if /i "%CMD%"=="restore"  goto :restore
if /i "%CMD%"=="reset"    goto :reset
echo Unknown command "%CMD%". Open bb.cmd in a text editor to see the list.
goto :fail

:start
echo.
echo  Starting Better Battle...
docker compose up -d --build
if errorlevel 1 goto :fail
echo  Waiting for the site to come up...
for /l %%i in (1,1,60) do (
  curl -s -o nul -f %URL%/bb/v1/health && goto :up
  timeout /t 2 /nobreak >nul
)
echo.
echo  The app did not answer after 2 minutes. Showing its logs:
docker compose logs --tail 60 app
goto :fail
:up
echo.
echo  Better Battle is running at %URL%
start "" %URL%
goto :done

:seed
set "GUILD=%~2"
if "%GUILD%"=="" set "GUILD=Aspiring"
docker compose exec app npm run seed-guild -- "%GUILD%"
goto :done

:backup_daily
if /i "%~2"=="off" goto :backup_daily_off
schtasks /create /f /sc daily /st 04:00 /tn "Better Battle backup" /tr "\"%~f0\" backup"
if errorlevel 1 (
  echo  Could not create the daily backup task.
  goto :fail
)
echo  Daily backup scheduled for 04:00.
goto :done

:backup_daily_off
schtasks /delete /f /tn "Better Battle backup"
if errorlevel 1 (
  echo  Could not remove the daily backup task.
  goto :fail
)
echo  Daily backup schedule removed.
goto :done

:check_db
docker compose exec -T db pg_isready -U bb -d betterbattle >nul 2>&1 <nul
if errorlevel 1 (
  echo  The database container is not running or is not ready. Run bb start first.
  exit /b 1
)
exit /b 0

:backup
call :backup_core || goto :fail
goto :done

:backup_core
call :check_db || exit /b 1
if not exist "backups" mkdir "backups"
if errorlevel 1 exit /b 1
set "STAMP="
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%t"
if not defined STAMP (
  echo  Could not create a backup timestamp.
  exit /b 1
)
set "DUMP=backups\betterbattle-!STAMP!.dump"
docker compose exec -T db pg_dump -U bb -d betterbattle -Fc -f /tmp/bb.dump
if errorlevel 1 (
  docker compose exec -T db rm -f /tmp/bb.dump >nul 2>&1
  echo  Database backup failed.
  exit /b 1
)
docker compose cp db:/tmp/bb.dump "!DUMP!"
if errorlevel 1 (
  docker compose exec -T db rm -f /tmp/bb.dump >nul 2>&1
  echo  Could not copy the database dump to !DUMP!.
  exit /b 1
)
docker compose exec -T db rm -f /tmp/bb.dump
if errorlevel 1 echo  Warning: backup was copied, but /tmp/bb.dump could not be removed.
set /a COUNT=0
for /f "delims=" %%F in ('dir /b /a-d /o-d "backups\*.dump" 2^>nul') do (
  set /a COUNT+=1
  if !COUNT! GTR 14 del /q "backups\%%F"
)
for %%F in ("!DUMP!") do (
  set "FULL_DUMP=%%~fF"
  set "SIZE=%%~zF"
)
echo  Backup saved to !FULL_DUMP! (!SIZE! bytes).
exit /b 0

:restore
set "BACKUP_FILE=%~2"
if not defined BACKUP_FILE (
  echo  Usage: bb restore ^<file^>
  goto :fail
)
if not exist "%BACKUP_FILE%" (
  echo  Backup file not found: %BACKUP_FILE%
  goto :fail
)
call :check_db || goto :fail
echo.
echo  WARNING: This replaces all current database data.
set /p "ANS=  Type RESTORE to continue: "
if not "!ANS!"=="RESTORE" ( echo  Cancelled. & goto :done )
docker compose stop app
if errorlevel 1 goto :fail
docker compose cp "%BACKUP_FILE%" db:/tmp/restore.dump
if errorlevel 1 goto :restore_error
docker compose exec -T db pg_restore -l /tmp/restore.dump >nul
if errorlevel 1 (
  echo  The backup file is invalid; the current database was not changed.
  goto :restore_error
)
call :backup_core
if errorlevel 1 (
  echo  Could not create a safety backup; the current database was not changed.
  goto :restore_error
)
echo  Safety backup of the current data: !FULL_DUMP!
docker compose exec -T db psql -U bb -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE betterbattle WITH (FORCE)" -c "CREATE DATABASE betterbattle"
if errorlevel 1 goto :restore_error
docker compose exec -T db psql -U bb -d betterbattle -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()"
if errorlevel 1 goto :restore_error
set "PG_RESTORE_WARN="
docker compose exec -T db pg_restore -U bb -d betterbattle --no-owner /tmp/restore.dump
if errorlevel 1 (
  set "PG_RESTORE_WARN=1"
  echo  pg_restore reported errors; continuing to timescaledb_post_restore. Review its output above.
)
docker compose exec -T db psql -U bb -d betterbattle -v ON_ERROR_STOP=1 -c "SELECT timescaledb_post_restore()"
if errorlevel 1 goto :restore_error
docker compose exec -T db rm -f /tmp/restore.dump
if errorlevel 1 goto :restore_error
docker compose start app
if errorlevel 1 (
  echo  The database was restored, but the app could not be restarted. Run bb start.
  goto :fail
)
if defined PG_RESTORE_WARN (
  echo  Restore ran post_restore, but pg_restore reported errors; review the output above.
  goto :fail
)
echo  Database restore completed.
goto :done

:restore_error
echo  Restore failed. Cleaning up and attempting to restart the app.
docker compose exec -T db rm -f /tmp/restore.dump >nul 2>&1
docker compose start app
goto :fail

:reset
echo.
echo  This deletes the database, all synced games and the leaderboard.
set /p "ANS=  Type DELETE to confirm: "
if not "!ANS!"=="DELETE" ( echo  Cancelled. & goto :done )
docker compose down -v
echo  All data deleted. Run bb start to begin fresh.
goto :done

rem --------------------------------------------------------------------------
:check_docker
where docker >nul 2>&1
if errorlevel 1 (
  echo.
  echo  Docker is not installed. Get Docker Desktop from:
  echo    https://www.docker.com/products/docker-desktop/
  start "" https://www.docker.com/products/docker-desktop/
  exit /b 1
)
docker info >nul 2>&1
if not errorlevel 1 exit /b 0
echo  Docker Desktop is not running. Starting it...
if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
for /l %%i in (1,1,60) do (
  timeout /t 3 /nobreak >nul
  docker info >nul 2>&1 && exit /b 0
)
echo  Docker Desktop did not start. Open it yourself, wait until it says "running", then try again.
exit /b 1

:check_env
if exist ".env" goto :env_ok
if exist ".env.txt" (
  echo  Found ".env.txt" - renaming it to ".env"
  ren ".env.txt" ".env"
  goto :env_ok
)
echo.
echo  First-time setup: no .env file yet.
set /p "KEY=  Paste your Hypixel API key: "
for /f %%p in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "PW=%%p"
> ".env" (
  echo DB_PASSWORD=!PW!
  echo HYPIXEL_API_KEY=!KEY!
  echo HYPIXEL_RATE=280
)
echo  Saved .env with a random database password.
:env_ok
findstr /r /c:"^HYPIXEL_API_KEY=..*" ".env" >nul || (
  echo  .env has no HYPIXEL_API_KEY. Add a line:  HYPIXEL_API_KEY=your-key
  exit /b 1
)
findstr /r /c:"^DB_PASSWORD=..*" ".env" >nul || (
  for /f %%p in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "PW=%%p"
  >> ".env" echo DB_PASSWORD=!PW!
  echo  Added a random DB_PASSWORD to .env
)
exit /b 0

rem --------------------------------------------------------------------------
:fail
echo.
if "%~1"=="" pause
exit /b 1
:done
if "%~1"=="" pause
exit /b 0
