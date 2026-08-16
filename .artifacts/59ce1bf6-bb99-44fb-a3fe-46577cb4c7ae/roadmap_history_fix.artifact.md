# Hoja de Ruta: Refactorización del Sistema de Historial de Música

Este documento detalla la estrategia para transformar el sistema de historial actual de uno basado en estados globales a uno **basado en eventos inmutables**. El objetivo es eliminar condiciones de carrera, evitar la pérdida de canciones en cambios rápidos y asegurar que la portada siempre corresponda al track correcto.

## 1. Diagnóstico de Problemas Críticos

| Problema | Impacto | Causa Raíz |
| :--- | :--- | :--- |
| **Portadas Desincronizadas** | 🔴 Crítico | Uso de `ALBUM_ART_RAW_FILE` como fuente global. La canción A se guarda con la portada de la canción B si B empezó a cargar antes del guardado de A. |
| **Pérdida de Canciones** | 🔴 Crítico | El sistema de "generaciones" cancela procesos previos. En cambios rápidos, las canciones intermedias nunca llegan al punto de "commit". |
| **Falsos Duplicados** | 🟠 Medio | Comparación agresiva basada solo en el título en `MusicDataStore`. |
| **trackKey Débil** | 🟠 Medio | Colisiones potenciales al usar solo `título + artista`. |
| **Dependencia del "Anterior"** | 🟠 Medio | El sistema solo guarda la canción anterior cuando llega una nueva, perdiendo datos si el listener se reinicia o falla un evento. |

---

## 2. Fase 1: Rediseño de Estructuras de Datos

### [MODIFICAR] [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
*   **MediaSnapshot Inmutable:** Crear una clase `MediaSnapshot` que capture: `trackKey`, `title`, `artist`, `album`, `durationMs`, `timestamp`, `playbackState` y un `ArtworkSource` (Sealed class: Bitmap, Uri o Placeholder).
    > [!IMPORTANT]
    > No almacenar Bitmaps pesados directamente en la cola si es posible; usar referencias o manejarlos con cuidado para evitar fugas de memoria.
*   **Mejora de `trackKey`:** Implementar un generador de hash: `packageName` + `title` + `artist` + `album` + `durationMs`.
*   **HistoryItem Robusto:** Añadir el `trackKey` al `HistoryItem` para comparaciones precisas y una referencia al archivo de imagen único.

---

## 3. Fase 2: Desacoplamiento de Flujos (UI vs Historial)

Actualmente, `processSnapshot` maneja tanto la actualización del widget como el historial. Debemos separarlos.

### [MODIFICAR] [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
*   **Canal FIFO para Historial:** Implementar un `Channel<MediaSnapshot>(capacity = Channel.BUFFERED)` con política de desbordamiento segura.
*   **Procesador de Historial:** Un worker que consuma este canal. **Nunca se cancela** por una nueva generación visual.
*   **Lógica de Disparo (Trigger):** Guardar la canción cuando se detecte como "Reproducida" (`STATE_PLAYING`), no solo cuando cambie el track.

---

## 4. Fase 3: Persistencia Segura y Naming Único

### [MODIFICAR] Lógica de Guardado
1.  **Eliminar dependencia de `ALBUM_ART_RAW_FILE`:** El historial nunca debe leer archivos globales compartidos.
2.  **Naming por TrackKey:** Guardar las imágenes como `history/art_{trackKey}.webp`. Esto asegura que la imagen pertenezca permanentemente a esa entidad.
3.  **Filtro Anti-Spam Inteligente:**
    ```kotlin
    val isSameTrack = old.trackKey == new.trackKey
    val isVeryRecent = abs(new.timestamp - old.timestamp) < 5000
    if (isSameTrack && isVeryRecent) return // Ignorar duplicado rápido
    ```

---

## 5. Fase 4: Integración y Validación

*   **Combinación de Fuentes:** Priorizar `MediaSession` (onMetadataChanged) pero mantener el `NotificationListener` como backup para reproductores legacy.
*   **Independencia del Artwork:** Un fallo al resolver la imagen **nunca** debe impedir que la canción se guarde en el historial. Se usa un placeholder.
*   **Regla de Oro:** Una vez creado el Snapshot, es **inmutable**. No depende de estados externos ni variables globales.

---

## 6. Plan de Verificación

### Pruebas de Estrés
- [ ] **Skip Spam:** Cambiar 10 canciones en menos de 5 segundos. Verificar que todas (o la mayoría válida) aparezcan en el historial con sus portadas correctas.
- [ ] **Sin Portada:** Verificar que las canciones sin arte no rompan la secuencia de imágenes del historial.
- [ ] **Re-entrada:** Abrir y cerrar el app de música repetidamente.

### Verificación Visual
- [ ] Confirmar que al cambiar de canción A -> B, el historial muestra inmediatamente A con la imagen que tenía A hace un segundo.

> [!IMPORTANT]
> El cambio más urgente es que `shiftHistoryImages()` deje de leer del disco (`ALBUM_ART_RAW_FILE`) y empiece a recibir la imagen procesada directamente desde el flujo de la notificación/sesión.
