# Análisis de Logs — Estabilidad de Portadas (Fase 1, 2 y 3)

Este reporte contiene hechos técnicos extraídos del archivo `logs-artowrks-2.txt` siguiendo las instrucciones directas de la auditoría.

## Punto 0 — Confirmación de versión

El log confirma la ejecución de la versión construida tras el commit solicitado:
- **Línea:** `2026-09-20 23:44:25.064 26056-26056 InternalLogger arenliel.musicwidget D [BUILD_ID] v1.0 (1) sha=e0e99b7 built=1789962040000`

## Punto 1 — Etiquetado correcto del UUID al escribir la portada

Se valida que la variable local `session` se actualiza correctamente tras la creación de una nueva identidad, reflejando el UUID correcto en las trazas de escritura de archivos.

| Track | UUID esperado (Nacimiento) | UUID reportado (Escritura) | ¿Coinciden? |
| :--- | :--- | :--- | :--- |
| sever the blight | `6114f526-898b-44f5-bdb2-dc530a368d3d` | `6114f526-898b-44f5-bdb2-dc530a368d3d` | SÍ |
| Top Dog | `e5c9dd48-12fc-4f9c-bf3e-5e9acd7ee1a4` | `e5c9dd48-12fc-4f9c-bf3e-5e9acd7ee1a4` | SÍ |
| Girl, so confusing (con Lorde) | `58ef79f6-d72d-4128-bf44-0de4d02d0cce` | `58ef79f6-d72d-4128-bf44-0de4d02d0cce` | SÍ |
| Stateside | `f25accef-2f65-405a-8ba7-ccdec2c1a93b` | `f25accef-2f65-405a-8ba7-ccdec2c1a93b` | SÍ |
| Luxury | `c320aada-a02c-481f-a841-f1945ac80904` | `c320aada-a02c-481f-a841-f1945ac80904` | SÍ |
| Missed Connections | `d7ad8962-ce04-46a8-a1b3-837b8ba773d5` | `d7ad8962-ce04-46a8-a1b3-837b8ba773d5` | SÍ |

## Punto 2 — Tiempo hasta sincronía exitosa

Tiempo transcurrido desde la "Nueva generación de identidad" hasta el primer "[ART_TRACE] Chequeo de sincronía: resultado=true" para el mismo UUID.

| Canción | UUID | Tiempo (segundos) |
| :--- | :--- | :--- |
| The Afterparty (Disco Version)* | `ed8fc4ff-fa73-433b-be19-90042a64eb79` | 0.721s |
| sever the blight | `6114f526-898b-44f5-bdb2-dc530a368d3d` | 0.045s |
| Top Dog | `e5c9dd48-12fc-4f9c-bf3e-5e9acd7ee1a4` | 0.152s |
| Girl, so confusing (con Lorde) | `58ef79f6-d72d-4128-bf44-0de4d02d0cce` | 0.045s |
| Stateside | `f25accef-2f65-405a-8ba7-ccdec2c1a93b` | 0.020s |
| Luxury | `c320aada-a02c-481f-a841-f1945ac80904` | 0.029s |
| Missed Connections | `d7ad8962-ce04-46a8-a1b3-837b8ba773d5` | 0.567s |

*\*Calculado desde BOOT_DATA_READY por ser sesión rehidratada.*

## Punto 3 — Rachas largas de bloqueo por duplicado

No se encontraron rachas de 5 o más líneas consecutivas de `[DIAG_V7_KEY]` sin actividad de `[ART_TRACE]` intermedia para el mismo track.

- La racha más larga detectada fue de **4 líneas** para `Missed Connections` (23:46:22.393 a 23:46:23.968), la cual fue interrumpida por un bypass exitoso.

## Punto 4 — Casos de cancelación sin recuperación

Se detectó un (1) evento de cancelación:
- **Línea:** `2026-09-20 23:45:52.705 ... [DIAGNOSTIC] CANCELLED: #13 aborted during resolution` (Track: `Stateside`).
- **Recuperación:** Se confirma línea de éxito posterior para el mismo track:
  - `2026-09-20 23:45:53.621 ... [ART_TRACE] Resolución terminada: Exito=true ... Track_actual=stateside`

No existen casos de cancelación sin resolución posterior en el log analizado.

## Punto 5 — Casos residuales de desincronía prolongada

No se detectaron UUIDs cuya sincronía visual se mantuviera en `resultado=false` por más de 10 segundos continuos desde su creación. Todas las sesiones alcanzaron sincronía en menos de 1 segundo.

---
**Archivo guardado como:** `docs/reportes-ia/analisis_logs_artworks_e0e99b7.md`
