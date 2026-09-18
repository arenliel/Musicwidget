# Orden de Implementación — Conjunto Identidad-Final (Parte 1) CON INSTRUMENTACIÓN

## Resumen de Cambios
Migración de la lógica de continuidad de `trackKey` (física, incluye duración) a `sessionIdentity` (negocio, sin duración) en 7 puntos críticos del sistema. Se incluye instrumentación `[IDENTITY_TRACE]` para validar el impacto de la duración en la toma de decisiones.

## Estado Inicial
- **Git:** `3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios`

---

## Implementación Paso a Paso

### Paso 1: `trackChangedUI`
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Cambio:** Sustitución de comparación directa de `trackKey` por `computeSessionIdentity`.
**Log:** `[IDENTITY_TRACE] Paso1_trackChangedUI`

### Paso 2: Artwork Fallback
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Cambio:** Uso de `computeSessionIdentity` en el bloque de rescate de portadas.
**Log:** `[IDENTITY_TRACE] Paso2_artworkFallback`

### Paso 3 & 3B: Letras (Zombie Detector, Silencio y Llamadas)
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Cambio:** 
- Actualización de `runLyricsShowcase` y `runPausedLyricsCycle` para recibir identidad de negocio.
- Ajuste de `currentRAM.trackKey != myTrackKey` a comparación de identidad.
- Ajuste de gating de silencio.
**Log:** `[IDENTITY_TRACE] Paso3_zombieDetector`, `[IDENTITY_TRACE] Paso3_silencio`

### Paso 4: `reconcileNewSession`
**Archivo:** [MusicStateProvider.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicStateProvider.kt)
**Cambio:** Uso de `computeSessionIdentity` para decidir si se inicia una nueva sesión de estado.
**Log:** `IDENTITY_TRACE Paso4_reconcileNewSession` (vía `android.util.Log`)

### Paso 5: `artworkKey` sin duración
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Cambio:** Eliminación de `$durationMs` de la clave de fallback de portadas.

### Paso 6: Letras Stage 1 Gating
**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Cambio:** Uso de `computeSessionIdentity` para decidir si se conservan las letras al persistir el snapshot lógico.
**Log:** `[IDENTITY_TRACE] Paso6_canKeepLyricStage1`

### Paso 7: Reloj Relativo
**Archivo:** [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
**Cambio:** Decisión de reseteo de reloj basada en identidad de negocio (título, artista, paquete) en lugar de `identityChanged` (que incluye duración).
**Log:** `IDENTITY_TRACE Paso7_shouldResetClock` (vía `android.util.Log`)

---

## Verificación de Implementación
- [ ] Compilación exitosa.
- [ ] Verificación de logs `[IDENTITY_TRACE]` en ejecución.
- [ ] Comprobación de persistencia de sesión ante cambios leves de duración.
