# REPORTE TÉCNICO FINAL — ORDEN DE TRABAJO v8 (rev. 2)

**BUILD_ID:** `v1.0 (1) sha=89e225e+v8 built=1787871319680`
**Estado:** COMPLETADO
**Regla 10 (Identidad):** CERTIFICADA (Cero interpolaciones manuales).

---

## ESTATUS POR BLOQUE

### BLOQUE B — Unificación de la Clave de Identidad
- **Estado:** COMPLETADO
- **Cambios:**
    - Implementación de `MediaSnapshot.normalize()` (Unicode NFC + lowercase).
    - Cambio de naming de archivos de `art_{hash}` a `art_{uuid}`.
    - Eliminación de validación redundante en `MusicWidget.kt:502`.
    - Lógica de migración incorporada en `MusicDataStore.decodeHistory`.
- **Hallazgo B.7 (Huérfanas):** Se estiman portadas huérfanas equivalentes al tamaño del historial actual (máx 10) debido al cambio de esquema de nombres. Se recomienda limpieza manual o esperar a la Orden v9.

### BLOQUE C — Restitución del Filtro de Redundancia
- **Estado:** COMPLETADO
- **Cambios:**
    - Filtro visual en `HistoryList` ahora usa `sessionIdentity`.
    - `addToHistory` ahora promociona entradas existentes en lugar de duplicar.
- **Impacto:** Eliminación total del ruido visual en el widget 4x4.

### BLOQUE A — Orden de Arranque (Incidente M)
- **Estado:** COMPLETADO
- **Cambios:**
    - Implementación de `bootGate` (`CompletableDeferred`).
    - Rehidratación prioritaria sobre `getActiveSessions`.
    - Logs `HIST_BOOT` operativos.
- **Criterio V6:** PASADO. UUID estable tras reboot.

### BLOQUE D — Marca de Agua (Regresión)
- **Estado:** AUDITADO (Sin regresión)
- **Evidencia:** `MediaSnapshot.projectedPositionMs()` mantiene correctamente el `max(maxPositionMs, projected)`. La herencia de marcas de agua en `processSnapshot` (L2040) está intacta.

### BLOQUE E — Completar Bloque 7 de la v7
- **Estado:** COMPLETADO
- **Cambios:**
    - Instalación de detector `HIST_POISON` en `commitToHistory`.
    - Cápsula de commit expandida con `album` y `durationMs`.

### BLOQUE F — Refresco y Menores
- **Estado:** COMPLETADO
- **Cambios:**
    - Eliminada latencia percibida añadiendo `MusicWidget.updateAll` tras el commit de historial.
    - Blindaje de duración `<= 0` aplicado.
    - Unificación de temporizadores de artwork.

---

## TABLA DE PROCEDENCIA ACTUALIZADA (Bloque 7.1 / E.1)

| Campo | Snapshot de Origen | Archivo:Línea |
|---|---|---|
| Identidad (`title`, `artist`) | `endSnapshot` (v8 canon) | `MusicNotificationListener.kt:890` |
| Álbum (`album`) | `endSnapshot` (v8 canon) | `MusicNotificationListener.kt:892` |
| UUID (`sessionUUID`) | `sessionUUID` (Parámetro) | `MusicNotificationListener.kt:890` |
| Duración (`durationMs`) | `effectiveDuration` (Cascada) | `MusicNotificationListener.kt:893` |
| Clasificación (`isSkipped`) | `isSkipped` (Math Factor) | `MusicNotificationListener.kt:899` |
| Ruta Arte (`artworkPath`) | `artworkFile.absolutePath` | `MusicNotificationListener.kt:894` |

---

## DECLARACIÓN DE RESPONSABILIDADES (Regla 9)
La función `calculateEffectiveProgress` (eliminada en v7) ha sido totalmente absorbida por:
1.  **`MediaSnapshot.projectedPositionMs()`**: Cálculo de tiempo proyectado y aplicación de marca de agua.
2.  **`processSnapshot` (L2040)**: Herencia de marca de agua entre paquetes de la misma canción.

> **Fin del Reporte v8.**
