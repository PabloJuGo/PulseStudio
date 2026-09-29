@echo off
rem Desarrollo: compila el backend y sirve la interfaz directamente desde src\main\resources\web
rem (edita index.html / styles.css / app.js y recarga la ventana con F5, sin recompilar).
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if exist build\dev rmdir /s /q build\dev
mkdir build\dev
dir /s /b src\main\java\*.java > build\dev-sources.txt
javac --release 17 -encoding UTF-8 -d build\dev @build\dev-sources.txt || exit /b 1
java -Djava.net.useSystemProxies=true -cp build\dev com.pulsestudio.desktop.PulseStudioApp --web=src\main\resources\web %*
endlocal
