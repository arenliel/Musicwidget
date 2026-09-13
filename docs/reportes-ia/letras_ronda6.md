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
661:                     val now = SystemClock.elapsedRealtime()
662:                     val processingLag = now - detectedAt
663:                     InternalLogger.d(applicationContext, "[LYRICS_TRACE] Aplicando Seek (Lag compensado: ${processingLag}ms): ${position + processingLag}ms")
664:                     
665:                     val updatedSnapshot = snapshot.copy(
666:                         positionMs = position + processingLag,
667:                         observedAtRealtime = now
668:                     )
669:                     lastLogicalSnapshot = updatedSnapshot
670:                     relaunchLyricsTicker("seek_event")
671:                 }
672:         }
673:     }
```

### 2. En `startBlacklistObserver` (Línea 699)
```kotlin
687:     private fun startBlacklistObserver() {
688:         serviceScope.launch {
689:             musicDataStore.musicInfoFlow.collect { info ->
690:                 val currentPkg = lastObservedSnapshot?.packageName
691:                 if (currentPkg != null && info.blacklist.contains(currentPkg)) {
...
698:                     lastAppliedSnapshot = null
699:                     lastLogicalSnapshot = null
700:                     lastObservedSnapshot = null
...
712:                 }
713:             }
714:         }
715:     }
```

### 3. En `onListenerConnected` (Línea 1143)
```kotlin
1105:     override fun onListenerConnected() {
...
1135:             InternalLogger.d(applicationContext, "[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.")
1136:             val currentInfo = musicDataStore.musicInfoFlow.first()
...
1139:             if (currentInfo.trackKey.isNotEmpty()) {
1140:                 // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
1141:                 val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
1142:                 lastLogicalSnapshot = recoveredSnapshot
1143:                 lastAppliedSnapshot = recoveredSnapshot
...
1222:     }
```

### 4. En `processSnapshot` (Línea 2214)
```kotlin
1906:     private suspend fun processSnapshot(
...
2212:         }
2213: 
2214:         // ACTUALIZACIÓN DEL DIARIO LÓGICO
2215:         lastLogicalSnapshot = rawSnapshot
2216:         lastObservedPositionMs = currentProjectedPos
...
2232: 
2233:         val isSessionEnded = sessionEnded
...
2288:                 
2289:                 if (isSessionEnded) {
2290:                     relaunchLyricsTicker("identity_change")
2291:                 } else {
2292:                     val stateChangedUI = currentMem.isPlaying != isPlaying
2293:                     if (stateChangedUI) relaunchLyricsTicker("state_sync")
2294:                 }
...
2610:     }
```

### 5. En `processSnapshot` (STAGE 2 - Inyección de Artwork) (Línea 2504)
```kotlin
2493:                             // Paso 3.2: CACHING DE TRANSFORMACIÓN
2494:                             if (savedArtworkKey != snapshot.artworkKey) {
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

1. **Línea 2214:** Se asigna el `rawSnapshot` entrante a `lastLogicalSnapshot` de forma síncrona.
2. **Línea 2237:** Se lanza una corrutina (`serviceScope.launch`) para actualizar la RAM (`MusicStateProvider`).
3. **Línea 2289-2292:** Dentro de esa corrutina, se llama a `relaunchLyricsTicker`.

**Análisis de ventana:**
Puesto que la asignación de la línea 2214 es síncrona y ocurre **antes** de lanzar la corrutina que invoca `relaunchLyricsTicker`, **no hay ventana** donde el ciclo de letras pueda arrancar viendo un valor antiguo de la canción anterior. Al momento de ejecutarse el cuerpo de `relaunchLyricsTicker`, `lastLogicalSnapshot` ya tiene el valor fresco.

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

**Comportamiento con letra vacía (`""`):**
Cuando se le pasa `""`, simplemente delega al `MusicStateProvider` mediante un evento `LyricTick`. El proveedor actualizará el estado de la RAM con la cadena vacía y, si esto causa un cambio en el estado, se emitirá un `StatusUpdate` para refrescar el widget (Glance), lo que efectivamente limpia la letra mostrada.
