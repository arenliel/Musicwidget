# Auditoría — Ronda 5 (Identidad): Origen de `old_mnl.kt`, Mapa de `durationMs`, y Resolución de Portada al Despertar tras Notificación Destruida

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AT1. Origen de `old_mnl.kt`

**Resultado de búsqueda en Git:**
El archivo `old_mnl.kt` **NO** aparece en el historial de Git (el comando `git log --diff-filter=A --follow -- old_mnl.kt` devolvió vacío).

**Estado actual (git status):**
```text
Untracked files:
  (use "git add <file>..." to include in what will be committed)
        old_mnl.kt
```

**Detalles del archivo en el sistema:**
- **Ruta:** `C:\Users\arenliel\AndroidStudioProjects\MusicWidget\old_mnl.kt`
- **Fecha de última modificación:** 11/9/2026 6:43 p. m.
- **Tamaño:** 298,334 bytes.

---

## AT2. Todos los usos de `durationMs` en decisiones de identidad/cambio

Excluyendo cálculos matemáticos de progreso o skip.

### 1. `MusicDataStore.kt`
Ubicado en `saveMusicInfo` (Línea 766). Decide si se debe resetear el reloj de tiempo relativo.

```kotlin
755:             val currentDurationMs =
756:                 prefs[DURATION_MS]
757:                     ?: 0L
...
766:             val identityChanged = currentTitle != info.title ||
767:                     currentArtist != info.artist ||
768:                     currentPackageName != info.packageName ||
769:                     currentTrackKey != info.trackKey ||
770:                     currentArtworkKey != info.artworkKey ||
771:                     currentArtworkUri != info.artworkUri ||
772:                     currentDurationMs != info.durationMs
```

---

### 2. `MusicNotificationListener.kt`

#### A. Construcción de Identidad Física (`trackKey`)
Ubicado en `createSnapshot` (Línea 1746) y en la propiedad `trackKey` (Línea 503).

```kotlin
1746:         val trackKeyStr = "$title|$artist|$duration"
```
```kotlin
502:         val trackKey: String
503:             get() = "$sessionIdentity|$durationMs"
```

#### B. Construcción de Identidad Visual (`artworkKey`)
Ubicado en la propiedad `artworkKey` (Línea 518).

```kotlin
515:         val artworkKey: String
...
518:                     ?: "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
```

#### C. Gating contra Amnesia de Doze (Línea 1941)
Ubicado en `processSnapshot` (Stage 1). Decide si el paquete es "degradado" para abortar el flujo.

```kotlin
1941:         val isDegraded = rawSnapshot.durationMs <= 0L
```

---

### 3. `LyricsRepository.kt`
Ubicado en `getLyrics` (Línea 37). Se usa como parámetro para la búsqueda en red.

```kotlin
37:         val networkResult = fetchFromNetwork(artist, title, durationMs / 1000)
```

---

## AT3. Resolución de portada al despertar tras notificación destruida

**Análisis técnico:**

1.  **Destrucción de Notificación:** Cuando la app de música destruye su notificación (ej. por sleep timer), el sistema invoca `onNotificationRemoved`. Esto dispara un `refreshBestSession(reason = "notification_removed")`.
2.  **Cierre Diferido:** Si la sesión estaba activa, el sistema la marca como `isPendingCommit = true` en el DataStore y emite `SessionEnded` (Línea 1400). En este punto, **el acceso al `MediaController` y sus metadatos se pierde**.
3.  **Encendido de Pantalla:** Al encender la pantalla, se ejecuta `onDisplayFullyVisible`, que lanza `refreshBestSession(reason = "catch_up_render")`.
4.  **Evaluación de Sesiones:** `refreshBestSession` consulta `mediaSessionManager.getActiveSessions()`. Como la notificación fue destruida, la lista estará **vacía** (Línea 1511).
5.  **Resultado:** La función retorna temprano tras un "WARM-UP DE DESPERTAR" (Línea 1513) que solo intenta cargar el bitmap del Disk Shield. **No se llama a `processSnapshot`**, por lo que **no existe ninguna oportunidad de ejecutar la resolución de Stage 2** (`resolveArtworkDeduplicated`) usando el controlador original.

**Excepción (Historial):**
La única resolución que ocurre al despertar es para los ítems del **historial** mediante `reconcilePendingHistoryArtworks()`. Esta función utiliza la **URI guardada** en el DataStore para intentar una descarga/decodificación (Línea 1063).

**Conclusión:** Si la notificación se destruye mientras la pantalla está apagada, la oportunidad de obtener una resolución de alta calidad desde el `controller` para la **sesión actual** desaparece definitivamente. El sistema solo podrá intentar resolver portadas para los registros que ya alcanzaron a entrar al historial con una URI válida.
