@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set OUT=runs\client\config\retroconsole\cores\.libheadless_gl.dll
set RES=src\main\resources\natives\libheadless_gl.dll
set SRC=headless_gl_win.c

if not exist "runs\client\config\retroconsole\cores" mkdir "runs\client\config\retroconsole\cores"
if not exist "src\main\resources\natives" mkdir "src\main\resources\natives"

set "GCC="

rem 1) PATH (where / where.exe)
where gcc >nul 2>&1 && set "GCC=gcc"
if not defined GCC (
  for /f "delims=" %%G in ('where x86_64-w64-mingw32-gcc 2^>nul') do (
    set "GCC=%%G"
    goto :have_gcc
  )
)

rem 2) WinGet WinLibs (BrechtSanders)
if not defined GCC if defined LOCALAPPDATA (
  for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\BrechtSanders.WinLibs*") do (
    if exist "%%D\mingw64\bin\gcc.exe" (
      set "GCC=%%D\mingw64\bin\gcc.exe"
      goto :have_gcc
    )
  )
)

rem 3) MSYS2 / common install locations
if not defined GCC if exist "C:\msys64\mingw64\bin\gcc.exe" set "GCC=C:\msys64\mingw64\bin\gcc.exe"
if not defined GCC if exist "C:\mingw64\bin\gcc.exe" set "GCC=C:\mingw64\bin\gcc.exe"
if not defined GCC if exist "C:\Program Files\mingw-w64\x86_64-8.1.0-win32-seh-rt_v6-rev0\mingw64\bin\gcc.exe" (
  set "GCC=C:\Program Files\mingw-w64\x86_64-8.1.0-win32-seh-rt_v6-rev0\mingw64\bin\gcc.exe"
)

:have_gcc
if not defined GCC (
  echo gcc not found — install MinGW-w64 or MSYS2 gcc
  echo   winget install -e --id BrechtSanders.WinLibs.POSIX.UCRT
  exit /b 1
)

echo Using compiler: %GCC%
"%GCC%" -shared -O2 -o "%OUT%" "%SRC%" -lopengl32 -lgdi32 -luser32
if errorlevel 1 exit /b 1

copy /Y "%OUT%" "%RES%" >nul
echo Built %OUT% and copied to %RES%
