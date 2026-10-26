@echo off
REM =============================================================================
REM 一键拉取 SynPharm 全部 4 仓库到同一父目录
REM
REM 用法：
REM   clone-all.bat [父目录] [git remote base URL]
REM   默认父目录：.\synpharm-workspace
REM   默认 git base：https://github.com/Ljz4777
REM
REM 拉取后启动开发环境：
REM   cd synpharm-workspace
REM   copy synpharm-deploy\.env.example .env
REM   docker compose -f synpharm-deploy\docker-compose.dev.yml up -d
REM =============================================================================

setlocal enabledelayedexpansion

set PARENT_DIR=%~1
if "%PARENT_DIR%"=="" set PARENT_DIR=.\synpharm-workspace

set GIT_BASE=%~2
if "%GIT_BASE%"=="" set GIT_BASE=https://github.com/Ljz4777

if not exist "%PARENT_DIR%" mkdir "%PARENT_DIR%"

for %%r in (synpharm-frontend synpharm-cloud synpharm-fastapi synpharm-deploy) do (
  echo === Cloning %%r ===
  if exist "%PARENT_DIR%\%%r\.git" (
    echo   %%r already exists, pulling latest...
    git -C "%PARENT_DIR%\%%r" pull --ff-only
    if errorlevel 1 echo   WARN: pull failed, check %%r manually
  ) else (
    git clone "%GIT_BASE%/%%r.git" "%PARENT_DIR%\%%r"
    if errorlevel 1 (
      echo   ERROR: clone %%r failed
      exit /b 1
    )
  )
)

echo.
echo === Done! 下一步：
echo   cd %PARENT_DIR%
echo   copy synpharm-deploy\.env.example .env
echo   REM 编辑 .env 配置密码/密钥
echo   docker compose -f synpharm-deploy\docker-compose.dev.yml up -d
endlocal
