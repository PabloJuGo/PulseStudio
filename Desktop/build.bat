@echo off
rem ==========================================================================
rem  Pulse Studio Desktop - compilar y generar PulseStudio.exe
rem    build.bat        -> dist\PulseStudio\PulseStudio.exe  (portable)
rem    build.bat exe    -> ademas instalador dist\PulseStudio-1.0.0.exe (WiX)
rem  Busca el JDK solo (JAVA_HOME, PATH o Program Files) y usa rutas completas.
rem ==========================================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "APP_NAME=PulseStudio"
set "APP_VERSION=1.0.0"
set "MAIN_CLASS=com.pulsestudio.desktop.PulseStudioApp"
set "MODE=%~1"
set "JDK="

echo.
echo  === Pulse Studio Desktop - build ===
echo.

rem --- 0. Comprobar que estamos en la carpeta del proyecto ---
if not exist "src\main\java\com\pulsestudio\desktop\PulseStudioApp.java" (
  echo [ERROR] No encuentro el codigo fuente junto a build.bat.
  echo         Descomprime el zip completo y ejecuta build.bat desde esa carpeta.
  goto :fail
)

rem --- 1. Localizar un JDK con javac + jpackage ---
if defined JAVA_HOME call :tryjdk "%JAVA_HOME%"
for /f "delims=" %%p in ('where javac 2^>nul') do call :tryjdk "%%~dpp.."
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do call :tryjdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Java\jdk-*") do call :tryjdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Microsoft\jdk-*") do call :tryjdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Zulu\zulu-*") do call :tryjdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Amazon Corretto\jdk*") do call :tryjdk "%%~fd"

if not defined JDK (
  echo [ERROR] No se ha encontrado ningun JDK 17+ con jpackage.
  echo         Instala Temurin 21 JDK desde https://adoptium.net
  echo         y marca "Add to PATH" y "Set JAVA_HOME" en el instalador.
  goto :fail
)

set "JAVAC=%JDK%\bin\javac.exe"
set "JAR=%JDK%\bin\jar.exe"
set "JPACKAGE=%JDK%\bin\jpackage.exe"

rem --- 2. Comprobar version (17 o superior) ---
"%JAVAC%" -version > "%TEMP%\pulse_javac_version.txt" 2>&1
set "JV="
for /f "usebackq tokens=2" %%v in ("%TEMP%\pulse_javac_version.txt") do set "JV=%%v"
set "JMAJOR=0"
for /f "delims=." %%m in ("%JV%") do set "JMAJOR=%%m"
echo  JDK encontrado: %JDK%
echo  Version javac:  %JV%
echo.
if %JMAJOR% LSS 17 (
  echo [ERROR] Este JDK es demasiado antiguo. Hace falta la version 17 o superior.
  goto :fail
)

rem Modulos del runtime que se empaqueta dentro del .exe
set "MODULES=java.base,java.desktop,java.logging,java.net.http,java.xml,jdk.httpserver,jdk.localedata"
if exist "%JDK%\jmods\jdk.crypto.ec.jmod" set "MODULES=%MODULES%,jdk.crypto.ec"

rem --- 3. Limpiar ---
echo [1/4] Limpiando carpetas anteriores...
if exist "build" rmdir /s /q "build"
if exist "dist\%APP_NAME%" rmdir /s /q "dist\%APP_NAME%"
if exist "dist\%APP_NAME%" (
  echo [ERROR] No se puede borrar dist\%APP_NAME%. Cierra Pulse Studio si esta abierto y vuelve a intentarlo.
  goto :fail
)
mkdir "build\classes"
mkdir "build\input"

rem --- 4. Compilar el backend ---
echo [2/4] Compilando el backend Java...
"%JAVAC%" --release 17 -encoding UTF-8 -d "build\classes" --source-path "src\main\java" "src\main\java\com\pulsestudio\desktop\PulseStudioApp.java"
if errorlevel 1 (
  echo [ERROR] Fallo al compilar. Revisa los mensajes de arriba.
  goto :fail
)
xcopy "src\main\resources" "build\classes\" /e /i /q /y >nul
if errorlevel 1 (
  echo [ERROR] No se pudo copiar la interfaz web a build\classes.
  goto :fail
)

rem --- 5. Crear el JAR ---
echo [3/4] Creando pulse-studio.jar...
"%JAR%" --create --file "build\input\pulse-studio.jar" --main-class %MAIN_CLASS% -C "build\classes" .
if errorlevel 1 (
  echo [ERROR] No se pudo crear el JAR.
  goto :fail
)

rem --- 6. Empaquetar con jpackage ---
echo [4/4] Empaquetando con jpackage (tarda 1-2 minutos)...
"%JPACKAGE%" --type app-image --name %APP_NAME% --app-version %APP_VERSION% --vendor JMOrdenadores --description "Pulse Studio" --input "build\input" --main-jar pulse-studio.jar --main-class %MAIN_CLASS% --icon "packaging\PulseStudio.ico" --add-modules %MODULES% --java-options -Djava.net.useSystemProxies=true --java-options -Dfile.encoding=UTF-8 --java-options -Xmx1g --dest "dist"
if errorlevel 1 (
  echo [ERROR] jpackage ha fallado. Revisa los mensajes de arriba.
  goto :fail
)
echo.
echo  OK: dist\%APP_NAME%\%APP_NAME%.exe
echo      Puedes copiar la carpeta dist\%APP_NAME% entera a otro PC.

if /i not "%MODE%"=="exe" goto :done

rem --- 7. Opcional: instalador (requiere WiX Toolset) ---
echo.
echo [extra] Generando instalador...
if exist "dist\%APP_NAME%-%APP_VERSION%.exe" del /q "dist\%APP_NAME%-%APP_VERSION%.exe"
"%JPACKAGE%" --type exe --app-image "dist\%APP_NAME%" --name %APP_NAME% --app-version %APP_VERSION% --vendor JMOrdenadores --win-menu --win-menu-group "Pulse Studio" --win-shortcut --win-dir-chooser --win-per-user-install --win-upgrade-uuid 6f1b3a52-8f3e-4c0e-9d0e-5a7c2b1f4e21 --dest "dist"
if errorlevel 1 (
  echo [ERROR] No se pudo crear el instalador. Necesitas WiX Toolset 3.14 en el PATH.
  echo         La version portable dist\%APP_NAME%\%APP_NAME%.exe si se ha creado bien.
  goto :fail
)
echo  OK: dist\%APP_NAME%-%APP_VERSION%.exe

:done
echo.
echo  Terminado sin errores.
echo.
pause
endlocal
exit /b 0

:fail
echo.
pause
endlocal
exit /b 1

rem --- Subrutina: acepta la carpeta si tiene javac.exe y jpackage.exe ---
:tryjdk
if defined JDK goto :eof
set "CAND=%~f1"
if exist "%CAND%\bin\javac.exe" if exist "%CAND%\bin\jpackage.exe" set "JDK=%CAND%"
goto :eof