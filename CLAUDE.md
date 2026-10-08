# Monster Gym POS

## Qué es
Sistema para gimnasio: socios, membresías, accesos (huella), caja y venta de refri.
Corre local en una PC Windows del gimnasio, sin depender de internet.

## Documentos de referencia (léelos antes de trabajar)
- docs/reglas-negocio.md : reglas del negocio, roles, turnos y caja.
- docs/modelo-datos.md   : esquema de base de datos y convenciones.
- docs/api.md            : endpoints, formatos y permisos.
Si una instrucción mía contradice estos documentos, avísame antes de continuar.

## Stack
- Java 21, Spring Boot 3, Maven, Spring JDBC (sin JPA)
- SQLite + Flyway (migraciones en src/main/resources/db/migration)
- Frontend: HTML/CSS/JS puro en src/main/resources/static (sin framework)
- Huella: DigitalPersona U.are.U 4500 (SDK de HID), detrás de la interfaz FingerprintReader
- Tickets: impresora térmica de 58 mm por ESC/POS, detrás de la interfaz TicketPrinter (EscPosPrinter y FakePrinter para desarrollo)

## Reglas técnicas
- Dinero siempre en centavos (INTEGER). Fechas ISO 8601. Zona America/Mexico_City.
- El estado de membresía (activa / por vencer / vencida) se calcula, nunca se guarda.
- Nunca editar una migración ya aplicada: crear una nueva.
- Textos de interfaz y mensajes de error en español.
- Todo endpoint valida entrada y permisos por rol.
- Cada cambio de lógica de negocio lleva pruebas.
- No guardar secretos ni PINs en claro; usar variables de entorno.
- Los datos de ejemplo (seed) solo existen en el perfil dev.
- Una falla de impresión nunca revierte un cobro ni un cierre de turno.

## Reglas del frontend
- NO cambiar clases CSS ni la estructura del HTML. Solo agregar ids o atributos data-* si hace falta.
- Conservar las funciones render* de app.js; que reciban los datos como parámetro.
- data.js se reemplaza poco a poco por llamadas a api.js, una pantalla a la vez.
- La API devuelve dinero en centavos; la conversión a pesos se hace al mostrar (función money).

## Comandos
- Correr: mvn spring-boot:run
- Pruebas: mvn test

## Flujo de trabajo
- Para cambios grandes: proponer un plan breve y esperar mi aprobación.
- Un commit por paso con mensaje claro, en español.
- Antes de terminar: correr las pruebas y la app, y decirme cómo verificarlo yo.
- Al terminar cada fase, marcarla abajo.

## Estado del proyecto
- [x] Fase 0: preparación del proyecto
- [x] Fase 1: base de datos
- [x] Fase 2: autenticación, empleados y turnos
- [x] Fase 3: socios, planes y cobros
- [ ] Fase 4: acceso, visitas e inicio
- [ ] Fase 5: refri (POS) y admin
- [ ] Fase 5B: tickets en impresora térmica 58 mm
- [ ] Fase 6: lector de huella
- [ ] Fase 7: entrega e instalación
