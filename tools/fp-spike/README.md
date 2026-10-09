# fp-spike — Fase 6A (prueba de concepto del lector de huella)

Programa de consola independiente (no usa Spring ni la base de datos) que:

1. Detecta el lector DigitalPersona U.are.U 4500.
2. Captura 4 muestras del mismo dedo y genera la plantilla de enrolamiento.
3. Captura una quinta huella y dice si coincide con la plantilla.

## Requisitos

- **One Touch for Windows SDK 1.6.1** (o el RTE) instalado. Pone:
  - los jars en `C:\Program Files\DigitalPersona\Bin\Java` (`dpotapi.jar`, `dpotjni.jar`);
  - las DLL JNI de 64 bits (`otdpfpjni.dll`, `otfxjni.dll`, `otmcjni.dll`) en `C:\Windows\System32`.
- JDK 21 o superior de 64 bits.

Los jars del SDK **no** se copian al repo: se leen de la instalación. Si están en otra ruta,
define la variable `DP_JAVA` antes de compilar.

## Uso

```
build.bat
run.bat
```

`run.bat` compila si hace falta. La plantilla queda en `out\plantilla.fpt` (ignorada por git).

## Clases del SDK usadas

| Paso | Clase / método |
|---|---|
| Punto de entrada (fábricas) | `DPFPGlobal` |
| Detectar lector | `DPFPGlobal.getReadersFactory().getReaders()` → `DPFPReadersCollection` / `DPFPReaderDescription` |
| Capturar | `DPFPCapture` (`startCapture`/`stopCapture`) + `DPFPDataListener` → `DPFPSample`; también `DPFPImageQualityListener` y `DPFPReaderStatusListener` |
| Extraer características | `DPFPFeatureExtraction.createFeatureSet(muestra, DPFPDataPurpose)` → `DPFPFeatureSet` (lanza `DPFPImageQualityException` si la imagen es mala) |
| Enrolar | `DPFPEnrollment` (`getFeaturesNeeded()` = 4, `addFeatures`, `getTemplateStatus`, `getTemplate`) → `DPFPTemplate` |
| Guardar / cargar | `DPFPTemplate.serialize()` → `byte[]`; `DPFPGlobal.getTemplateFactory().createTemplate(byte[])` |
| Comparar | `DPFPVerification.verify(featureSet, template)` → `DPFPVerificationResult` (`isVerified`, `getFalseAcceptRate`) |

## Formato de la plantilla

- `serialize()` devuelve un `byte[]` **opaco y propietario de DigitalPersona** (no es ANSI 378 ni ISO 19794-2).
  Solo lo entienden los SDK de DigitalPersona (One Touch Windows/Linux, Gold).
- Para guardarla basta una columna `BLOB` en SQLite. El tamaño se imprime al correr el spike.
- Se reconstruye con `createTemplate(bytes)`; el spike guarda los bytes en un archivo, los vuelve a leer
  y verifica contra la copia leída para comprobar que todo el ciclo funciona.
- Es un dato biométrico: no reversible a imagen, pero sensible. No debe salir de la PC del gimnasio.

## Notas para la Fase 6B

- Este SDK solo hace **verificación 1:1**: no trae identificación 1:N. Para "¿de quién es esta huella?"
  hay que recorrer todas las plantillas con `verify()`. El spike mide cuánto tarda cada comparación.
- `MEDIUM_SECURITY_FAR` = 1 en 100 000 (el valor que usa el ejemplo del SDK).
- El SDK es de 2010 (pide JRE 1.5/1.6), pero funciona con un JDK moderno de 64 bits.
