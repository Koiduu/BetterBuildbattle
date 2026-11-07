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
rem    bb reset            DELETE all data and start fresh
rem ==========================================================================
cd /d "%~dp0"
set "URL=http://localhost:4000"
set "CMD=%~1"
if "%CMD%"=="" set "CMD=start"

call :check_docker || goto :fail
call :check_env || goto :fail

if /i "%CMD%"=="start"    goto :start
if /i "%CMD%"=="stop"     ( docker compose stop & goto :done )
if /i "%CMD%"=="restart"  ( docker compose up -d --force-recreate app & goto :done )
if /i "%CMD%"=="logs"     ( docker compose logs -f --tail 100 app & goto :done )
if /i "%CMD%"=="status"   ( docker compose ps & goto :done )
if /i "%CMD%"=="seed"     goto :seed
if /i "%CMD%"=="snapshot" ( docker compose exec app npm run snapshot & goto :done )
if /i "%CMD%"=="update"   ( docker compose up -d --build & goto :done )
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
