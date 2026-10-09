@echo off
setlocal
rem Instala los jars del One Touch for Windows SDK 1.6.1 en el repositorio local de Maven (~/.m2).
rem Hay que correrlo una vez en cada PC donde se compile el proyecto. Los jars no se suben al repo.
rem Las DLL nativas las instala el SDK o el RTE de DigitalPersona en C:\Windows\System32.
if "%DP_JAVA%"=="" set DP_JAVA=C:\Program Files\DigitalPersona\Bin\Java
if not exist "%DP_JAVA%\dpotapi.jar" (echo No se encontro "%DP_JAVA%\dpotapi.jar". Instala el SDK o define DP_JAVA. & exit /b 1)
cd /d "%~dp0.."
call "%~dp0..\mvnw.cmd" -q install:install-file -Dfile="%DP_JAVA%\dpotapi.jar" -DgroupId=com.digitalpersona -DartifactId=dpotapi -Dversion=1.6.1 -Dpackaging=jar || exit /b 1
call "%~dp0..\mvnw.cmd" -q install:install-file -Dfile="%DP_JAVA%\dpotjni.jar" -DgroupId=com.digitalpersona -DartifactId=dpotjni -Dversion=1.6.1 -Dpackaging=jar || exit /b 1
echo SDK de huella instalado en el repositorio local de Maven.
