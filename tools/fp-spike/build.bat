@echo off
setlocal
rem Compila el spike contra los jars del SDK instalado (no se copian al repo).
if "%DP_JAVA%"=="" set DP_JAVA=C:\Program Files\DigitalPersona\Bin\Java
cd /d "%~dp0"
if not exist build mkdir build
javac --release 21 -encoding UTF-8 -cp "%DP_JAVA%\dpotapi.jar;%DP_JAVA%\dpotjni.jar" -d build src\FpSpike.java
if errorlevel 1 (echo Error al compilar & exit /b 1)
echo Compilado en build\
