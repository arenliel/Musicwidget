# Auditoría — Ronda 10: Cobertura de Ramas en Variables de Estado Compartido

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## BB1. Cobertura de ramas para `lastAppliedSnapshot`

Ubicada en `MusicNotificationListener.kt`.

### 1. En `startBlacklistObserver` (Línea 698)
```kotlin
690:             musicDataStore.musicInfoFlow.collect { info ->
691:                 val currentPkg = lastObservedSnapshot?.packageName
692:                 if (currentPkg != null && info.blacklist.contains(currentPkg)) {
693:                     InternalLogger.d(applicationContext, "[BLACKLIST_PURGE] App actual $currentPkg ha sido añadida a la lista negra. Purgando.")
...
698:                     lastAppliedSnapshot = null
...
701:                     savedAppIconKey = null
702:                 }
703:             }
```
- **Contexto:** Dentro de un bloque `if` (línea 692) que verifica si la app actual está en la lista negra.
- **Ramas hermanas:** No existe bloque `else`. Si la condición es falsa, no se realiza ninguna asignación.

### 2. En `onListenerConnected` (Línea 1144)
```kotlin
1139:             if (currentInfo.trackKey.isNotEmpty()) {
1140:                 // RESTAURAR SESIÓN LÓGICA (v7.0: Sesión Provisional al arranque)
1141:                 val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
1142:                 lastLogicalSnapshot = recoveredSnapshot
1143:                 lastAppliedSnapshot = recoveredSnapshot
...
1165:                 InternalLogger.d(applicationContext, "[DIAGNOSTIC] Punteros de estado y RAM rehidratados desde DataStore.")
1166:             }
```
- **Contexto:** Dentro de un bloque `if` (línea 1139) que verifica si hay una pista persistida.
- **Ramas hermanas:** No existe bloque `else`. Si el DataStore está vacío, la variable mantiene su valor inicial (`null`).

### 3. En `processSnapshot` (Línea 2590)
```kotlin
2414:             kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
2415:                 commitMutex.withLock {
...
2590:                     lastAppliedSnapshot = snapshot
2591:                     lastObservedSnapshot = snapshot
2592:                     lastCommittedInfo = MusicStateProvider.current()
2593:                 }
2594:             }
```
- **Contexto:** Incondicional dentro del `commitMutex` de la STAGE 2.
- **Ramas hermanas:** El flujo solo llega aquí si la pantalla está visible (`if (!isWidgetPotentiallyVisible())` en la línea 2335 no hizo `return`).

### 4. En `onDestroy` (Línea 3208)
```kotlin
3208:         lastAppliedSnapshot = null
```
- **Contexto:** Incondicional al final del ciclo de vida del servicio.

---

## BB2. Cobertura de ramas para `degradedStreakStartRealtime`

### 1. En `processSnapshot` (Línea 1938)
```kotlin
1936:         if (isLatent && isBaseIdentityMatch && isDegraded && !currentMem.isEmpty) {
1937:             val now = SystemClock.elapsedRealtime()
1938:             val streakStart = degradedStreakStartRealtime ?: now.also { degradedStreakStartRealtime = it }
...
1959:         }
1960:         degradedStreakStartRealtime = null
```
- **Contexto:** La asignación de valor (línea 1938) ocurre solo si el paquete está degradado.
- **Ramas hermanas:** El bloque `if` no tiene `else`. Sin embargo, la variable se resetea incondicionalmente a `null` en la línea 1960 si el paquete **no** está degradado (o si el flujo continúa después del gatekeeper).

---

## BB3. Cobertura de ramas para `liveSnapshot`

### 1. En `onListenerConnected` (Línea 1152)
```kotlin
1148:                 currentLogicalSession = LogicalSession(
...
1152:                     liveSnapshot = recoveredSnapshot,
...
1161:                 )
```
- **Contexto:** Asignación vía constructor dentro de `if (currentInfo.trackKey.isNotEmpty())`.

### 2. En `processSnapshot` (Nueva Sesión) (Línea 2176)
```kotlin
2173:             val newSession = LogicalSession(
...
2176:                 liveSnapshot = rawSnapshot,
...
2181:             )
```
- **Contexto:** Dentro del bloque `if (sessionEnded && !isCatchUpRender)` (línea 2126).

### 3. En `processSnapshot` (Fusión) (Línea 2209)
```kotlin
2201:         } else {
2202:             // FUSIÓN DE ESTADO (v6.5): Solo actualizamos liveSnapshot y contexto
...
2208:             session?.let { s ->
2209:                 s.liveSnapshot = rawSnapshot
2210:                 s.playbackContext = updatedContext
2211:             }
2212:         }
```
- **Contexto:** Dentro de la rama `else` de la decisión de fin de sesión.
- **Ramas hermanas:** La rama `if` (línea 2126) crea una sesión nueva (ver punto 2 arriba), por lo que `liveSnapshot` se actualiza en ambos caminos.

---

## BB4. Cobertura de ramas para `lyricsTrackKey` y `currentLyric`

Estas variables son propiedades de `MusicInfo` y se asignan durante la creación de objetos para el DataStore o RAM.

### 1. En `processSnapshot` (Gating Visual) (Líneas 2307-2325)
```kotlin
2298:         if (!isWidgetPotentiallyVisible()) {
...
2304:                 val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2305:                 val finalLyricKey = if (canKeepLyric) currentInfo.lyricsTrackKey else ""
2306: 
2307:                 val logicalMusicInfo = MusicInfo(
...
2317:                     currentLyric = finalLyric,
2318:                     lyricsTrackKey = finalLyricKey,
...
2325:                 )
```
- **Contexto:** Dentro de la rama `if (!isWidgetPotentiallyVisible())`.
- **Ramas hermanas:** La rama `else` (widget visible) continúa hacia la STAGE 2.

### 2. En `processSnapshot` (Compromiso Final) (Líneas 2543-2575)
```kotlin
2538:                     val finalLyric = if (canKeepLyric) currentInfo.currentLyric else ""
2539:                     val finalLyricKey = if (canKeepLyric) snapshot.trackKey else ""
...
2543:                     val finalMusicInfo = MusicInfo(
...
2554:                         currentLyric = finalLyric,
2555:                         lyricsTrackKey = finalLyricKey,
...
2567:                     )
```
- **Contexto:** Incondicional al final de STAGE 2.
- **Ramas hermanas:** Solo se alcanza si el widget es visible.

**Resumen de Hermanas (BB4):**
- Si el widget **no es visible**: Se usa la lógica de preservación/limpieza de las líneas 2304-2305.
- Si el widget **es visible**: Se usa la lógica equivalente de las líneas 2538-2539.
- En ambos casos, las variables de letra se actualizan basándose en la condición `canKeepLyric`.
