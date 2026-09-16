# Auditoría — Ronda 17 (Portadas): ¿Podemos Reutilizar `previousLogical` en Vez de Reubicar `previousApplied`?

**Confirmación de Git Log (HEAD):**
```
dcb4f54 (HEAD -> master) Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
c10562a Conjunto Artwork-3: Guardar imagen resuelta con verificación de identidad (identityGenerationCounter based)
```

---

## AN1. Ubicación actual de ambas variables

Ubicadas en `MusicNotificationListener.kt`:

- **`previousLogical` (Línea 1977):**
```kotlin
1977:         val previousLogical =
1978:             lastLogicalSnapshot
```

- **`previousApplied` (Línea 2368):**
```kotlin
2368:         val previousApplied = 
2369:             lastLogicalSnapshot
```

---

## AN2. Contexto completo desde el inicio de `processSnapshot` hasta la declaración de `previousLogical`

Ubicado en `MusicNotificationListener.kt` (Líneas 1913 - 1980).

```kotlin
1913:     private suspend fun processSnapshot(
1914:         controller: MediaController?,
1915:         metadata: MediaMetadata?,
1916:         rawSnapshot: MediaSnapshot,
1917:         reason: String
1918:     ) {
1919:         // v8.0: Bloqueo proactivo hasta que la rehidratación termine (BLOQUE A)
1920:         kotlinx.coroutines.withTimeoutOrNull(BOOT_GATE_TIMEOUT_MS) { bootGate.await() }
1921:             ?: InternalLogger.w(applicationContext, "[HIST_BOOT] BOOT_GATE_TIMEOUT: procesando paquete vivo ($reason) sin estado rehidratado")
1922: 
1923:         val session = currentLogicalSession
1924:         
1925:         val stateName = when(rawSnapshot.playbackState) {
1926:             PlaybackState.STATE_PLAYING -> "PLAYING"
1927:             PlaybackState.STATE_PAUSED -> "PAUSED"
1928:             else -> "OTHER(${rawSnapshot.playbackState})"
1929:         }
1930:         InternalLogger.d(applicationContext, "[DIAG_V5] [INTAKE] Recibido: Estado=$stateName, Track=${rawSnapshot.title}, Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}ms, Reason=$reason")
1931: 
1932:         // --- STAGE 1: RESOLUCIÓN DE ESTADO (EJECUCIÓN SIEMPRE ACTIVA) ---
1933: 
1934:         val currentMem = MusicStateProvider.current()
1935: 
1936:         // FILTRO DE MUTACIÓN DEGRADADA (v4.7.1 - Gatekeeper contra Amnesia de Doze Mode)
1937:         // Si el sistema está en pausa y el OS envía metadatos incompletos para la misma canción,
1938:         // abortamos para proteger el estado coherente en RAM y Disco.
1939:         val isLatent = rawSnapshot.playbackState != PlaybackState.STATE_PLAYING || !rawSnapshot.isSessionActive
1940:         val isBaseIdentityMatch = rawSnapshot.title == currentMem.title && rawSnapshot.artist == currentMem.artist
1941:         val isDegraded = rawSnapshot.durationMs <= 0L
1942: 
1943:         if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
1944:             val now = SystemClock.elapsedRealtime()
1945:             val streakStart = degradedStreakStartRealtime ?: now.also { degradedStreakStartRealtime = it }
1946:             val elapsed = now - streakStart
1947: 
1948:             if (elapsed < BUFFERING_THRESHOLD_MS) {
1949:                 InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=${rawSnapshot.album}, Duración=${rawSnapshot.durationMs}")
1950:                 return
1951:             }
1952: 
1953:             InternalLogger.w(applicationContext, "[DIAG_V5] [GATEKEEPER] Umbral de ${BUFFERING_THRESHOLD_MS}ms superado. Activando estado Cargando.")
1954:             serviceScope.launch {
1955:                 val bufferingEvent = MusicUpdateEvent.StatusUpdate(
1956:                     isPlaying = false,
1957:                     deviceName = currentMem.playbackDeviceName,
1958:                     deviceType = currentMem.playbackDeviceType,
1959:                     isBuffering = true
1960:                 )
1961:                 if (MusicStateProvider.applyEvent(bufferingEvent)) {
1962:                     uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
1963:                 }
1964:             }
1965:             return
1966:         }
1967:         degradedStreakStartRealtime = null
1968: 
1969:         /*
1970:          * Creamos una nueva generación de forma atómica.
1971:          */
1972:         val myGeneration =
1973:             generation.incrementAndGet()
1974: 
1975:         // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
1976:         // Esto permite que el historial use la fuente de verdad del último estado aplicado.
1977:         val previousLogical =
1978:             lastLogicalSnapshot
```

**Confirmación:** La variable `previousLogical` se declara antes de cualquier actualización de estado en la Stage 1. Captura correctamente el estado previo real.

---

## AN3. Todos los usos actuales de `previousApplied`

Coincidencias en `MusicNotificationListener.kt`:

1.  **Declaración (Línea 2368):** `val previousApplied = lastLogicalSnapshot`.
    **Nota Crítica:** En este punto, `lastLogicalSnapshot` ya fue actualizado con `rawSnapshot` en la línea 2224. Por lo tanto, `previousApplied` **NO** contiene el estado previo, sino el estado actual recién aplicado.
2.  **Comparación de identidad (Línea 2372):** `previousApplied?.trackKey != snapshot.trackKey`.
3.  **Cambio para letras (Línea 2377):** `val songChangedForLyrics = previousApplied == null || ...`.
4.  **Cálculo de desvío (Línea 2469):** `val effectivePos = previousApplied?.projectedPositionMs() ?: 0L`.

### Confirmación de Reemplazo:
**SÍ.** Es válido y necesario reemplazar `previousApplied` por `previousLogical` en todos estos puntos. 

**Justificación:**
1.  **Tipo de dato:** Ambas variables son de tipo `MediaSnapshot?`.
2.  **Veracidad:** `previousLogical` captura el estado **antes** de la actualización de la Stage 1, mientras que `previousApplied` captura el estado **después** de esa actualización (lo cual invalida las comparaciones de cambio, ya que compara el snapshot nuevo contra sí mismo).
3.  **Saneamiento:** Al usar `previousLogical`, el sistema volverá a detectar correctamente los cambios de pista, app y metadatos que actualmente se están ignorando en la Stage 2 debido al error de captura tardía de `previousApplied`.
