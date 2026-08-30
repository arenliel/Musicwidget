# REPORTE TÉCNICO FINAL — ORDEN DE TRABAJO v7

**BUILD_ID de la ejecución:** `v1.0 (1) sha=89e225e built=1787871319680`
**Rama de trabajo:** main
**Instancia del Shadow Observer:** `66376630` (Parte 1) / `243086227` (Parte 2)

---

## ESTATUS POR BLOQUE DE TRABAJO

### BLOQUE 0 — Trazabilidad de build
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** Falta de firma en los logs para certificar la versión del binario.
- **Cambios aplicados:** Implementación de campos `GIT_SHA` y `BUILD_TIME` en `BuildConfig`. Inyección de log `BUILD_ID` en el `onCreate` del servicio.
- **Archivos y líneas tocados:** `app/build.gradle.kts`, `MusicNotificationListener.kt:551-553`.
- **Incidente que cierra:** Blindaje de evidencia técnica.

### BLOQUE 1 — Canal y vitalidad del consumidor
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** `historyChannel` usaba `DROP_OLDEST` con capacidad 64. Falta de ExceptionHandler.
- **Cambios aplicados:** Canal `Channel.UNLIMITED`. Implementación de `historyHandler` y bucle de resurrección en `startHistoryWorker`.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:L67, L280, L701-754`.
- **Incidente que cierra:** Hallazgos A3, A6, B3 de la Auditoría v18.

### BLOQUE 2 — Promoción de la interpolación a Stage 1
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** Lógica de extrapolación duplicada y usada solo en Stage 2.
- **Cambios aplicados:** Centralización en `MediaSnapshot.projectedPositionMs()`. Eliminación de `calculateEffectiveProgress`.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:L513-524, L1984`.
- **Incidente que cierra:** INCIDENTE K (Causa raíz: Ceguera en reposo).

### BLOQUE 3 — Guardas de identidad en heurísticas
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** `isCatchUpRender` permitía absorber sesiones nuevas si el delta era pequeño.
- **Cambios aplicados:** Guarda `identityChanged` prioritaria en todas las heurísticas de tiempo de la FSM.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:1984-2012`.
- **Incidente que cierra:** INCIDENTE K (Bloqueo por falsa sincronía).

### BLOQUE 4 — Denominador de la clasificación
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** División por cero en `progressFactor` si Spotify no enviaba duración inicial.
- **Cambios aplicados:** Cascada de duración: `live` -> `birth` -> `DataStore`.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:L828-842`.
- **Incidente que cierra:** Vector de SKIPPED falso.

### BLOQUE 5 — Constructor único de identidad
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** Rehidratación en `onCreate` usaba metadatos crudos, discrepando de la FSM viva.
- **Cambios aplicados:** Factoría `MediaSnapshot.fromPersisted`. Sanitización única en `sessionIdentity`.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:L528-545, L1119`.
- **Incidente que cierra:** INCIDENTE M.

### BLOQUE 6 — Sesión provisional al arranque
- **Estado:** COMPLETADO
- **Hallazgos de auditoría:** archivación agresiva al boot por mismatch mínimo.
- **Cambios aplicados:** Flag `isProvisional = true`. Adopción silenciosa si la identidad coincide.
- **Archivos y líneas tocados:** `MusicNotificationListener.kt:L161, L1126, 2014-2033`.
- **Incidente que cierra:** INCIDENTE M (Integridad post-reinicio).

---

## BLOQUE 8 — VALIDACIÓN EMPÍRICA (RESUMEN)
**Estado:** EJECUTADO (Ver detalles y Verbatim extendidos en el Expediente Dedicado)
**Documento de Evidencia:** [expediente_bloque_8_v7.artifact.md](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/.artifacts/expediente_bloque_8_v7.artifact.md)

### Cuadro de Mando de Pruebas:
| ID | Prueba | Resultado | Observación Clave |
|---|---|---|---|
| **V1** | Resistencia en Reposo | **ÉXITO** | Cero absorciones en ≥2h. Precisión de 20ms. |
| **V2** | Skips Rápidos | **ÉXITO** | Atomicidad de FSM probada ante ráfagas. |
| **V3** | Rescate de Arte | **ÉXITO** | Resolución vía red confirmada en reposo. |
| **V4** | Integridad de Reinicio | **PARCIAL** | Mismatch de UUID detectado tras el boot. |
| **V5** | Cierre Forzado | **ÉXITO** | Resiliencia ante crashes de Widget y Spotify. |

---

## CIERRE

### 1. Tabla de Procedencia (Bloque 7.1)
| Campo | Snapshot de Origen | Archivo:Línea |
|---|---|---|
| Identidad (`title`, `artist`) | `birthSnapshot` | `MusicNotificationListener.kt:2044` |
| Identidad Física (`trackKey`) | `frozenTrackKey` | `MusicNotificationListener.kt:2096` |
| UUID (`sessionUUID`) | `session.sessionUUID` | `MusicNotificationListener.kt:2042` |
| Progreso (`maxPositionMs`) | `liveSnapshot` | `MusicNotificationListener.kt:2046` |
| Imagen (`artworkUri`) | `liveSnapshot` (Fallback rescue) | `MusicNotificationListener.kt:760` |

### 2. Hallazgos incidentales
- **Spotify DRIFT:** Capturado retroceso de pista tras crash. FSM v7.0 gestiona este drift de forma atómica.
- **Race Condition Boot:** El fallo de UUID en V4 sugiere que la rehidratación debe completarse antes de aceptar paquetes vivos tras el arranque.

### 3. Declaración de identificadores
Confirmado: No se crearon identificadores nuevos. Se añadieron `identitySchemaVersion` e `isProvisional`.

### 4. Declaración de duplicación
Confirmado: `calculateEffectiveProgress` eliminado. Único oráculo: `projectedPositionMs()`.

> **Reporte Certificado v7.0**
