@echo off
setlocal

set "EHVIEWER_BATCH_ORIGINAL_VERSION=1"

call "%~dp0_build.bat" release %*

set "BUILD_EXIT=%ERRORLEVEL%"
if not "%BUILD_EXIT%"=="0" goto Finish

:Install
adb install -r "%~dp0app\build\outputs\apk\appRelease\release\app-appRelease-release.apk"
set "INSTALL_EXIT=%ERRORLEVEL%"
if "%INSTALL_EXIT%"=="0" (
    endlocal
    exit /b 0
)

echo ADB install failed with exit code %INSTALL_EXIT%.
choice /c 12 /m "Retry ADB install?"
if errorlevel 2 (
    set "BUILD_EXIT=%INSTALL_EXIT%"
    goto Finish
)
goto Install

:Finish
endlocal & exit /b %BUILD_EXIT%
