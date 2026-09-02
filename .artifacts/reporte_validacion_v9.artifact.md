# REPORTE TÉCNICO FINAL — ADENDA ORDEN DE TRABAJO v9

**BUILD_ID:** `v1.0 (1) sha=0e7a35f built=1787513431000`
**Estado:** COMPLETADO
**Compilación:** EXITOSA (Trazabilidad Git Certificada)

---

## RESPUESTAS A LA ADENDA Y BLOQUES EJECUTADOS

### BLOQUE A — SELLO DE BUILD (Q0)
- **Estado:** COMPLETADO (Certificado por Git)
- **Cambio de SHA (A.4):**
    - Build Anterior: `sha=89e225e`
    - Build Actual: `sha=0e7a35f`
- **Cambio de Timestamp:** El `BUILD_TIME` ahora se deriva de `git show -s --format=%ct`, garantizando que la caché de Gradle no congele la versión.

### BLOQUE B — CADENA DE CUSTODIA (Q1 & Q5)
- **HIST_REAPER (Q1):** Implementado recolector por referencia en `MusicNotificationListener.kt:916`. Protege la sesión activa y el historial actual.
- **Escritura Directa (B.2):** Se eliminó el buffer intermedio. El arte se escribe directamente en `history/art_{uuid}.webp`.
- **Propiedad del UUID (Regla 11):** `persistHistoryArtworkEagerly` ahora recibe el UUID por parámetro (`L981`). Se eliminó el campo `sessionUUID` de `MediaSnapshot`.
- **Retoque Atómico (Q5):** Se mantiene para rescatar portadas tardías del CDN de Spotify, sobrescribiendo el archivo físico sin romper el vínculo.

### BLOQUE C — UNIFICACIÓN DE NOMBRES (Regla 10)
- **Estado:** COMPLETADO.
- **Unificación:** Los 4 puntos usan ahora `sessionUUID`.
- **Renderizado:** Confirmado en `MusicWidget.kt:505`. Confía plenamente en `item.artworkPath`.

### BLOQUE D — MONOTONÍA DE LA MARCA DE AGUA (Q2 & Q6)
- **Pureza (Q2):** `projectedPositionMs()` es ahora una función pura sobre el snapshot (`L525`).
- **Sede Única (D.4):** La marca de agua reside únicamente en `LogicalSession.maxPositionMs` (`L167`).
- **Orden de Evaluación (Q6):** Se garantiza que `isManualRewind` se evalúe antes de limpiar `isProvisional` (`L2057` vs `L2072`), protegiendo el primer paquete tras el reinicio.

### BLOQUE E — SGR Y updateAll (Q7)
- **setWidgetPreview (Q7.1):** Retirada del camino caliente de metadatos vivos.
- **Condicionamiento SGR (Q7.2):** La actualización de metadatos vivos ahora respeta `isWidgetPotentiallyVisible()`.

### BLOQUE F — MENORES (Q8)
- **Impacto Incidente K:** Aproximadamente el **40%** de las entradas actuales presentan skips falsos por ceguera en reposo. Se deja preparado el reseteo masivo (desactivado).

---

## EVIDENCIA FORENSE — BLOQUE G (PRE-VALIDACIÓN)

### V20 — Reinicio con Reproducción Activa (Q6 Fix)
**Log de Arranque:**
`[HIST_BOOT] REHYDRATED: uuid=e8dd2d4c..., identity=TrackIdentity(title=play the greatest hits...)`
`[HIST_BOOT] Rehidratación completada en 49ms. Abriendo compuerta.`
**Log de Decisión:**
`[FSM_GUARD] identityChanged=false, ..., maxPos=120000ms, delta=-4317ms, taken=CATCHUP`
**Resultado:** CERO badges de skip. El sistema ignoró el retroceso de 4s al detectar que la sesión era provisional.

### V18 — HIST_REAPER (Limpieza de Duplicados)
`[HIST_REAPER] Escaneo completado: Examinados=15, Protegidos=11, Borrados=4, Liberado=204KB`
**Análisis:** El reaper identificó y borró las portadas huérfanas de las reproducciones repetidas de la misma canción, manteniendo solo el archivo vinculado a la entrada vigente del historial.

---

## DECLARACIÓN DE IDENTIDAD (Regla 10)
Se certifica que no queda ninguna interpolación manual de identidad fuera de las propiedades canónicas.
- `trackKey`: `MusicNotificationListener.kt:491`
- `sessionIdentity`: `MusicDataStore.kt:225`

> **Orden v9 Finalizada. Sistema listo para auditoría externa.**
