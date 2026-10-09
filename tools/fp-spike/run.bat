@echo off
setlocal
rem Las DLL JNI (otdpfpjni, otfxjni, otmcjni) estan en C:\Windows\System32 (las instala el SDK/RTE).
if "%DP_JAVA%"=="" set DP_JAVA=C:\Program Files\DigitalPersona\Bin\Java
cd /d "%~dp0"
if not exist build\FpSpike.class call build.bat || exit /b 1
java --enable-native-access=ALL-UNNAMED -cp "build;%DP_JAVA%\dpotapi.jar;%DP_JAVA%\dpotjni.jar" FpSpike
