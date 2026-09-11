# Auditoría — Ronda 5: Mecanismo de Re-búsqueda de Letra ante Refinamiento de Metadatos

Confirmando el estado del proyecto:
```
c9c5b7c (HEAD -> master) Conjunto Letras-2: Regla Unificada de Silencio (10 segundos)
12b87e4 Conjunto Letras-1: Identidad Unificada para Preservación de Letra
6e5278f Conjunto F.2: Implementación del intérprete de Buffering en el Gatekeeper
77ff7a9 Conjunto F.1: Andamiaje del estado Cargando (Buffering)
bf6f590 Conjunto E: Implementación del Reloj de Verdad usando señales oficiales de PlaybackState
```

---

## P1. Dónde y cómo se calcula `trackChangedUI`

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2354-2358)

```kotlin
        val previousApplied = 
            lastAppliedSnapshot

        val trackChangedUI = 
            previousApplied?.trackKey != snapshot.trackKey
```

**Confirmación:** La variable `trackChangedUI` compara los `trackKey` de los snapshots. Dado que `trackKey` incluye la duración (`$sessionIdentity|$durationMs`), cualquier refinamiento en la duración de la pista disparará este flag como `true`, incluso si se trata de la misma canción.

---

## P2. Código completo del bloque de re-búsqueda de letra

Este bloque se encuentra dentro de `processSnapshot` y se dispara cuando `trackChangedUI` es verdadero.

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) (Líneas 2415-2433)

```kotlin
            if (trackChangedUI) {
                InternalLogger.d(applicationContext, "[LYRICS_TRACE] Cambio de track detectado. Reiniciando sesión.")
                lyricsUpdateJob?.cancel()
                lyricsFetchJob?.cancel()
                currentLyrics = null
                
                lyricsFetchJob = serviceScope.launch {
                    // PUNTO B: Debounce para evitar spam de API
                    delay(500L)
                    
                    val result = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
                    if (result != null && isActive) {
                        currentLyrics = result
                        relaunchLyricsTicker("identity_change")
                    } else if (isActive) {
                        // PUNTO E: Fallback Silencioso - Si falla la API, limpiamos el widget
                        InternalLogger.d(applicationContext, "[LYRICS_TRACE] Fallback Silencioso: No se encontraron letras.")
                        updateLyricInWidget(snapshot.trackKey, "")
                    }
                }
            }
```

---

## P3. ¿Es el mismo camino que ya se corrigió, o uno distinto?

**Análisis:** Es un **camino distinto**.

Aunque ambos residen en `processSnapshot` (Stage 2) de `MusicNotificationListener.kt`, el cambio realizado en el **Conjunto Letras-1** afectó a la lógica de **preservación** del estado de la letra en los objetos `MusicInfo` y el `MusicStateProvider` (línea ~2519), mientras que este bloque (línea 2415) es la lógica de **activación de búsqueda (Fetch)**.

El problema detectado es que el flag `trackChangedUI` es demasiado sensible (basado en `trackKey`), lo que provoca que un refinamiento de metadatos (como la llegada de la duración exacta) cancele la sesión de letras actual y lance una búsqueda de red innecesaria para la misma canción, ignorando el hecho de que ya podríamos tener las letras cargadas en `currentLyrics`.

---
**Nota:** Este documento contiene únicamente evidencia de código extraída mediante auditoría técnica. No se han realizado cambios funcionales.
