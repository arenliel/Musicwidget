# Auditoría — Ronda 6 (Letras): Frescura de `lastLogicalSnapshot` al Iniciar Sesión, y Función de Limpieza de Letra

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## EE1. Todos los lugares donde se asigna `lastLogicalSnapshot`, con contexto amplio

Ubicados en `MusicNotificationListener.kt`.

### 1. En `startSeekEventProcessor` (Línea 669)
```kotlin
656:     private fun startSeekEventProcessor() {
657:         serviceScope.launch {
658:             seekEventFlow
659:                 .debounce(400L)
660:                 .collect { (snapshot, position, detectedAt) ->
...
664:                     val updatedSnapshot = snapshot.copy(
665:                         positionMs = position + processingLag,
666:                         observedAtRealtime = now
667:                     )
668:                     lastLogicalSnapshot = updatedSnapshot
669:                     relaunchLyricsTicker("seek_event")
670:                 }
671:         }
672:     }
```

### 2. En `startBlacklistObserver` (Línea 699)
```kotlin
687:     private fun startBlacklistObserver() {
688:         serviceScope.launch {
689:             musicDataStore.musicInfoFlow.collect { info ->
...
696:                     // 2. Limpieza en Memoria del Listener (v2.2)
697:                     lastAppliedSnapshot = null
698:                     lastLogicalSnapshot = null
699:                     lastObservedSnapshot = null
...
703:                 }
704:             }
705:         }
706:     }
```

### 3. En `onListenerConnected` (Línea 1143)
```kotlin
1105:     override fun onListenerConnected() {
...
1139:             if (currentInfo.trackKey.isNotEmpty()) {
1140:                 // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
1141:                 val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
1142:                 lastLogicalSnapshot = recoveredSnapshot
1143:                 lastAppliedSnapshot = recoveredSnapshot
...
1165:             }
...
1186:     }
```

### 4. En `processSnapshot` (Stage 1) (Línea 2214)
```kotlin
1906:     private suspend fun processSnapshot(...) {
...
2201:         } else {
2202:             // FUSIÓN DE ESTADO (v6.5): Solo actualizamos liveSnapshot y contexto
...
2208:             session?.let { s ->
2209:                 s.liveSnapshot = rawSnapshot
2210:                 s.playbackContext = updatedContext
2211:             }
2212:         }
2213: 
2214:         // ACTUALIZACIÓN DEL DIARIO LÓGICO
2215:         lastLogicalSnapshot = rawSnapshot
2216:         lastObservedPositionMs = currentProjectedPos
...
2351:     }
```

### 5. En `processSnapshot` (Stage 2 - Actualización de Artwork) (Línea 2504)
```kotlin
2484:                             if (savedArtworkKey != snapshot.artworkKey) {
...
2501:                                 // Hallazgo v4.2: Artwork Relay (Inyección de Píxeles)
2502:                                 // Inyectamos el bitmap en el snapshot lógico para que la próxima 
2503:                                 // transición de historial lo lleve ya resuelto.
2504:                                 lastLogicalSnapshot = lastLogicalSnapshot?.copy(
2505:                                     artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
2506:                                 )
2507:                             }
```

---

## EE2. ¿Cuándo, exactamente, recibe su primer valor al iniciar una sesión nueva?

En el flujo de una canción nueva (`processSnapshot`):

1.  **Línea 2215:** Se asigna el valor fresco (`rawSnapshot`) a `lastLogicalSnapshot`. Esto ocurre de forma síncrona en el cuerpo principal de `processSnapshot`.
2.  **Línea 2237:** Se lanza una corrutina (`serviceScope.launch`) para actualizar la RAM (`mutationMutex.withLock`).
3.  **Línea 2284:** Se aplica el evento a la RAM (`MusicStateProvider.applyEvent`).
4.  **Línea 2289:** Se llama a `relaunchLyricsTicker("identity_change")`.

**Ventana de riesgo:** No hay ventana de riesgo en cuanto a la asignación. `lastLogicalSnapshot` se actualiza en la línea **2215**, mucho antes de que el ticker de letras arranque en la línea **2289** (especialmente considerando que el ticker arranca dentro de una corrutina lanzada después de la asignación). Cuando el ticker comienza y eventualmente llama a `runLyricsShowcase`, `lastLogicalSnapshot` ya tiene el valor de la nueva canción.

---

## EE3. Código completo de `updateLyricInWidget`

```kotlin
2715:     private fun updateLyricInWidget(trackKey: String, lyric: String) {
2716:         // Relevo Atómico (v4.0)
2717:         serviceScope.launch {
2718:             if (MusicStateProvider.applyEvent(MusicUpdateEvent.LyricTick(lyric, trackKey))) {
2719:                 uiUpdateFlow.tryEmit(UpdateEvent.StatusUpdate)
2720:             }
2721:         }
2722:     }
```

**Comportamiento con `""`:** Envía un evento `LyricTick("", trackKey)` al `MusicStateProvider`. Si esto provoca un cambio en el estado (limpiando la letra anterior), emite un `StatusUpdate` para refrescar el widget.

---

## EE4. ¿Cuándo se captura el identificador que recibe `runLyricsShowcase`?

Llamada en `relaunchLyricsTicker` (Línea 2633):

```kotlin
2608:     private fun relaunchLyricsTicker(reason: String) {
...
2613:         val currentInfo = MusicStateProvider.current()
...
2623:         // Hallazgo v3.8: Ticker Stateless (Claude). Lee identidad y estado directo de la RAM.
2624:         lyricsUpdateJob = serviceScope.launch(Dispatchers.IO) {
2625:             val lyricsRes = lyricsRepository.getLyrics(
2626:                 currentInfo.trackKey, 
2627:                 currentInfo.artist, 
2628:                 currentInfo.title, 
2629:                 currentInfo.durationMs
2630:             ) ?: return@launch
2631: 
2632:             if (currentInfo.isPlaying) {
2633:                 runLyricsShowcase(currentInfo.trackKey, lyricsRes)
2634:             } else {
2635:                 runPausedLyricsCycle(currentInfo.trackKey, lyricsRes)
2636:             }
2637:         }
2638:     }
```

**Confirmación:**
- El valor se captura en la línea **2613** (`val currentInfo = MusicStateProvider.current()`).
- Esta captura ocurre **antes** de lanzar la corrutina (`launch` en 2624) y, por lo tanto, **antes** de la llamada a `getLyrics`. 
- No hay un `delay` explícito en esta función antes de la búsqueda, aunque `getLyrics` puede tardar por ser una operación de red. El identificador queda "congelado" en la variable local `currentInfo` antes de iniciar cualquier proceso asíncrono.
