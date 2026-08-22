# INFORME COMPLETO DE AUDITORÍA TÉCNICA (v4.2)
## Subsistema de Adquisición de Artworks, Integridad de Sesión y Historial

**Fecha:** 20 de Agosto de 2026
**Estatus:** Verificado - Smart Mirror v4.2 ("Gobernanza de Integridad")

---

## 1. ESPECIFICACIÓN TÉCNICA Y ARQUITECTURA SSOT

El subsistema de adquisición y persistencia del Music Widget ha sido diseñado bajo el paradigma de **Gobernanza de Integridad**. El objetivo principal es garantizar que el usuario nunca perciba inconsistencias visuales y que el consumo de recursos sea mínimo.

### 1.1 Capas de Verdad Subordinadas
1.  **RAM Volátil (`MusicStateProvider`)**: Verdad instantánea para interactividad (Letras, Ticker de tiempo).
2.  **Persistencia Transaccional (`MusicDataStore` / Disco)**: Verdad física y persistente (Bitmaps, Identidad).
3.  **Binder / Glance UI (RemoteViews)**: Espejo visual reactivo, subordinado a la validación de llaves físicas en disco.

---

## 2. AUDITORÍA DE INTEGRACIÓN DE HISTORIAL & SCREEN-GATED RENDERING (SGR)

### BLOQUE 1: COMPORTAMIENTO EN REPOSO (SCREEN-GATED)

**1.1. En MusicNotificationListener.kt, con pantalla APAGADA (`isWidgetPotentiallyVisible() == false`):**
*   **a) ¿Llamadas a red o decodificación para el Historial?**
    **SÍ.** La lógica de historial está desacoplada del renderizado visual del widget. La llamada a `historyChannel.trySend(historySnapshot)` ocurre en el **Stage 1** (línea 1338), antes del check de visibilidad. El historial debe registrarse independientemente de si el usuario está mirando el widget.
*   **b) ¿Omisión de Stage 2?**
    **SÍ.** La ejecución del **Stage 2** (procesamiento visual pesado) se interrumpe prematuramente:
    ```kotlin
    // MusicNotificationListener.kt:1365
    if (!isWidgetPotentiallyVisible()) {
        isPresentationDirty = true
        pendingSnapshot = snapshot
        lyricsUpdateJob?.cancel() // Suspensión física de CPU
        lastObservedSnapshot = snapshot
        return // Fin del Stage 2
    }
    ```
    Esto ahorra batería críticamente al no generar píldoras rotadas ni realizar I/O de imagen innecesario.

**1.2. Flujo de `catch_up_render` (Desbloqueo):**
*   **a) Código exacto**: Se ejecuta al detectar que el widget vuelve a ser visible:
    ```kotlin
    // MusicNotificationListener.kt:1099
    private fun onDisplayFullyVisible() {
        serviceScope.launch {
            refreshBestSession(reason = "catch_up_render")
        }
    }
    ```
*   **b) Alcance**: Intenta resolver **únicamente la canción ACTUAL**. Las canciones del historial ya fueron procesadas asíncronamente. El objetivo es "rellenar" el Stage 2 omitido para que la UI principal se actualice instantáneamente tras el desbloqueo.

### BLOQUE 2: CONCURRENCIA Y CANDADOS (commitMutex vs. Historial)

**2.1. Función `processHistoryEvent()`:**
*   **a) ¿Dentro de `commitMutex`?** **NO.**
*   **b) Ejecución**: Se ejecuta de forma asíncrona en el `startHistoryProcessor`, consumiendo eventos del `historyChannel` (línea 1170). Esto evita que el procesamiento del historial bloquee el pipeline de actualización de la UI.

**2.2. Obtención de Artwork en cambios rápidos:**
*   **a) Referencia**: Recibe un `MediaSnapshot` con un `artworkSource`.
*   **b) Fuente**: Utiliza una referencia en memoria (`ArtworkSource.Bitmap`) si el Stage 2 anterior tuvo éxito, o intenta la resolución vía URI si no:
    ```kotlin
    // MusicNotificationListener.kt:1183
    val bitmap = when (val source = snapshot.artworkSource) {
        is ArtworkSource.Bitmap -> source.bitmap // Memoria (Relevo)
        is ArtworkSource.Uri -> decodeAlbumArtUri(source.uri) // Red/Provider
        ArtworkSource.Placeholder -> null
    }
    ```

### BLOQUE 3: IDENTIDAD DE PISTAS Y ASSETS

**3.1. Construcción de `trackKey`:**
*   **a) Fórmula**: `"$title|$artist|$durationMs"`. **No incluye `packageName`** (identidad puramente musical).
*   **b) Nombramiento**: Los archivos de historial se guardan usando el hash de la identidad: `art_{trackKey.hashCode()}.webp` (línea 1179).

**3.2. Decodificación de miniaturas:**
*   **a) Función**: Se usa `ImageUtils.createHorizontalPill` (línea 1197) con dimensiones escaladas por densidad:
    ```kotlin
    val w = (80 * density).toInt()
    val h = (40 * density).toInt()
    val historyPill = ImageUtils.createHorizontalPill(finalBitmap, w, h)
    ```

### BLOQUE 4: MECANISMO DE BENDICIÓN (BLESSED)

**4.1. Consulta de Inmunidad:**
*   **a) Fuente**: Consulta directamente la **RAM** a través del SSOT volátil:
    ```kotlin
    // MusicNotificationListener.kt:1208
    val currentRAM = MusicStateProvider.current()
    val isBlessed = currentRAM.history.any { it.trackKey == snapshot.trackKey && !it.isSkipped }
    ```
    Esto garantiza rapidez en la toma de decisiones analíticas sin esperar al I/O del DataStore.

---

## 3. GESTIÓN DE CONCURRENCIA Y COMMIT SEGURO

### 3.1 Subordinación al Commit (Anti-Flicker)
Glance solo recibe el evento de redibujado tras la liberación del `commitMutex` en el `Listener`.
**Justificación**: Previene el parpadeo visual donde el texto cambia antes que la imagen.

### 3.2 Sistema de Llaves Digitales (`album_art.key`)
El widget valida la llave física contra la RAM:
```kotlin
val isArtworkSynchronized = displayedInfo.artworkKey.trim() ==
    readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim()
```
Si no coinciden, se suprime el renderizado del bitmap para evitar mostrar arte incoherente.

---

## 4. BINDER SAFETY Y ESCRITURA ATÓMICA

### 4.1 Prevención de `TransactionTooLargeException`
- Portadas escaladas estrictamente a **800px**.
- Miniaturas del historial decodificadas con `inSampleSize` a **~120px**.
- Inyección proactiva en `bitmapCache` antes del redibujado de Glance (Binder Warm-up).

### 4.2 Protocolo de Escritura en FS
Uso de archivos `.tmp` y `Files.move` con `StandardCopyOption.ATOMIC_MOVE`.
**Lógica**: Garantiza que el cambio de puntero en el FS sea instantáneo, protegiendo los archivos contra corrupciones si el sistema suspende el proceso durante la escritura.

---

## 5. MATRIZ DE DO's Y DON'Ts (v4.2)

| ✅ DO's (Obligatorio) | ❌ DON'Ts (Intolerable) |
| :--- | :--- |
| Validar `album_art.key` antes de renderizar bitmaps en Glance. | Realizar I/O de archivos en el hilo de composición de Glance. |
| Cancelar Tickers de letras en reposo (`isInteractive == false`). | Confiar en el `MediaController` para obtener arte de canciones pasadas. |
| Usar `StandardCopyOption.ATOMIC_MOVE` para llaves de sincronía. | Pasar bitmaps de >800px a través del bus Binder. |
| Mantener el historial desacoplado del `commitMutex` de la UI. | Actualizar la RAM con datos de sesión nueva antes del commit de disco. |

---
*Fin del Informe Maestro de Auditoría.*
