# REPORTE TÉCNICO FINAL — ORDEN DE TRABAJO v9

**BUILD_ID:** `v1.0 (1) sha=89e225e built=1787513431000`
**Estado:** COMPLETADO
**Regla 11 (Propiedad de Datos):** CUMPLIDA. `sessionUUID` desacoplado de los snapshots.

---

## ESTATUS POR BLOQUE

### BLOQUE A — Sello de build
- **Estado:** COMPLETADO
- **Cambios:** Ambos campos (`GIT_SHA` y `BUILD_TIME`) se derivan ahora exclusivamente de git en `app/build.gradle.kts`. Esto rompe la caché de configuración y garantiza trazabilidad real.
- **Evidencia:** `built=1787513431000` (Timestamp de git del último commit).

### BLOQUE B — Cadena de custodia (hotline.)
- **Estado:** COMPLETADO
- **Cambios:**
    - Se eliminó `cleanupHistoryBuffer` de `onCreate`.
    - La persistencia proactiva y el commit escriben ahora **directamente** en la ruta definitiva `history/art_{sessionUUID}.webp` (`MusicNotificationListener.kt:823, 988`).
    - Se eliminó la dependencia de archivos intermedios en el Shadow Observer.
- **Regla 11:** `persistHistoryArtworkEagerly` recibe ahora el UUID por parámetro (`L974`). El UUID ha sido eliminado de `MediaSnapshot`.

### BLOQUE C — Unificación de nombres (Regla 10)
- **Estado:** COMPLETADO
- **Cambios:** Se unificaron los 4 puntos de generación de nombres (Eager, Commit, Catch-up y Renderizado) para usar el `sessionUUID` de la sesión.
- **Archivo:Línea:**
    - `MusicNotificationListener.kt:823` (Commit)
    - `MusicNotificationListener.kt:988` (Eager)
    - `MusicNotificationListener.kt:1052` (Catch-up)

### BLOQUE D — Monotonía de la marca de agua
- **Estado:** COMPLETADO
- **Cambios:**
    - `maxPositionMs` reside ahora únicamente en `LogicalSession` (`L167`).
    - Invariante impuesta en `processSnapshot` (`L2053`): `s.maxPositionMs = max(s.maxPositionMs, currentProjectedPos)`.
    - Se ignora `isManualRewind` durante sesiones provisionales (Arranque) para evitar skips falsos (`L2050`).
- **Regla 9:** `MediaSnapshot` es ahora una estructura puramente inmutable y sin estado de progreso acumulado.

### BLOQUE E — updateAll y SGR
- **Estado:** COMPLETADO
- **Cambios:** La llamada a `MusicWidget.updateAll` en el commit de historial ahora está condicionada por `isWidgetPotentiallyVisible()` (`L935`).
- **Auditoría API:** Se confirma que el historial NO utiliza `setWidgetPreview`. Las llamadas detectadas en v8 correspondían al refresco de metadatos vivos en Android 15.

### BLOQUE F — Menores
- **Estado:** COMPLETADO
- **Cambios:** Implementado `GLOBAL_SKIP_STREAK_RESET_ENABLED` en `MusicDataStore.kt:265` (desactivado por defecto).

---

## TABLA DE PROCEDENCIA (Regla 9)

| Función Anterior | Destino Actual | Responsabilidad |
|---|---|---|
| `calculateEffectiveProgress` | `MediaSnapshot.projectedPositionMs()` | Cálculo de tiempo y marca de agua instántanea. |
| `calculateEffectiveProgress` | `LogicalSession.maxPositionMs` | Almacenamiento persistente del récord de progreso. |
| `cleanupHistoryBuffer` | **ELIMINADA** | La escritura directa a la ruta final hace innecesario el buffer. |

---

## DECLARACIÓN DE IDENTIDAD (Regla 10)
Se certifica que no queda ninguna interpolación manual de identidad (`$title|$artist...`) fuera de las propiedades canónicas de `MediaSnapshot` y `HistoryItem`.
- `trackKey` canon: `MediaSnapshot.kt:491`
- `sessionIdentity` canon: `MediaSnapshot.kt:485`

> **Fin del Reporte v9.**
