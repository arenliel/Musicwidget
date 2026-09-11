# Auditoría — Ronda 4: Confirmación de la Barrera de Entrada (`contentKey`)

## Estado del Proyecto (git log)

```text
bb7b30a (HEAD -> master) Docs: descriptive identity and sync comments (Phase 1)
45753c8 Conjunto B: Proyección final de posición al cierre de sesión para asegurar precisión en racha (pantalla apagada)
9e55bad Conjunto B.1: actualización de posición real (Verdad) previa al guarda de deduplicación
```

---

## D1. Definición completa de `contentKey`
Ubicación: `MusicNotificationListener.kt` (Líneas 522-527, dentro de `MediaSnapshot`)

```kotlin
        /*
         * Identidad completa del snapshot.
         * Incluye la posición redondeada para detectar Seeks significativos.
         */
        val contentKey: String
            get() = "$trackKey|$artworkKey|$playbackState|${projectedPositionMs() / 1000}"
```
**Análisis:** El `contentKey` depende de `projectedPositionMs() / 1000`. Esto significa que si la posición proyectada no cambia lo suficiente como para saltar al siguiente segundo entero (como ocurre durante un buffering real donde la posición de reproducción está estancada), y los demás metadatos son idénticos, el `contentKey` será el mismo durante toda la ráfaga de callbacks de estado.

---

## D2. Asignaciones a `lastObservedSnapshot` e `inFlightSnapshot`
Ubicación: `MusicNotificationListener.kt`

**lastObservedSnapshot:**
- `line 698`: `lastObservedSnapshot = null` (Limpieza en rehidratación/reset)
- `line 1976`: `lastObservedSnapshot = rawSnapshot` (Dentro de la guarda de RAM deduplication en `processSnapshot`)
- `line 2318`: `lastObservedSnapshot = snapshot` (Gating de visibilidad de pantalla en `processSnapshot`)
- `line 2546`: `lastObservedSnapshot = snapshot` (Al final de un procesamiento exitoso en `processSnapshot`)
- `line 3152`: `lastObservedSnapshot = null` (Destrucción del servicio)

**inFlightSnapshot:**
- `line 699`: `inFlightSnapshot = null` (Limpieza)
- `line 2558`: `inFlightSnapshot = null` (Final del bloque `finally` en `processSnapshot`)
- `line 3154`: `inFlightSnapshot = null` (Destrucción)

**Nota:** No se encontró ninguna línea donde se asigne un valor no nulo a `inFlightSnapshot` en el código actual, lo que sugiere que esa parte de la barrera en `refreshBestSession` podría estar inactiva o incompleta.

---

## D3. Verificación de duplicación en el Gatekeeper
Ubicación: `MusicNotificationListener.kt` (Rango 1920-1940)

```kotlin
1926:        val currentMem = MusicStateProvider.current()
1927:
1928:        // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
1929:        // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
1930:        // abortamos para proteger el estado coherente en RAM y Disco.
1931:        val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
1932:        val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
1933:        val isDegraded = rawSnapshot.durationMs <= 0L
1934:
1935:        if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
1936:            InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
1937:            return
1938:        }
```
**Resultado:** La duplicación de la línea `isBaseIdentityMatch` **no existe** en el archivo original. Fue un error de transcripción en la respuesta de la Ronda 3.

---

## D4. Contexto de la guarda de deduplicación de RAM
Ubicación: `MusicNotificationListener.kt` (Línea 1965)

```kotlin
        // Detección de Incoherencia de Imagen: Si la portada en disco no coincide con la del snapshot, forzamos bypass
        val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()

        // Hallazgo 4.1: RAM-Fringe Deduplication (v3.1)
        // Bloqueamos ráfagas antes de entrar al Mutex o realizar cálculos analíticos.
        if (!isCatchUp && !trackContentChanged && !artIncoherent && 
            currentMem.isPlaying == (rawSnapshot.playbackState == PlaybackState.STATE_PLAYING) && 
            currentMem.isSessionActive == rawSnapshot.isSessionActive) {
            
            // REGLA B.1 (v9.1): Actualización de Verdad (Posición) previa al Redibujado.
            // Aseguramos que la marca de agua progrese aunque el refresco visual sea ignorado.
            session?.let { s ->
                s.maxPositionMs = Math.max(s.maxPositionMs, rawSnapshot.projectedPositionMs())
            }

            // Si el widget es visible pero el contenido es idéntico a la RAM, ignoramos.
            lastObservedSnapshot = rawSnapshot
            return
        }
```
**Análisis:** Esta guarda (Línea 1965) es un **segundo nivel de silencio**. Incluso si un paquete supera la barrera de `refreshBestSession` (por ejemplo, porque el `contentKey` cambió un segundo después), si el estado de reproducción (`isPlaying`) y la actividad de la sesión no han cambiado respecto a lo que ya está en RAM (`currentMem`), el flujo se aborta silenciosamente **antes** de emitir logs de `[DIAG_V5] [INTAKE]`. Esto confirma que el silencio observado es el resultado de dos capas de deduplicación agresiva.
