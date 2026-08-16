# Documentación Maestro: Sistema de Historial de Reproducción

Este documento describe el funcionamiento técnico integral del historial de reproducción del Music Widget. El sistema ha sido diseñado bajo una arquitectura reactiva, inmutable y orientada a la integridad de datos, garantizando una sincronización perfecta entre metadatos, portadas y análisis de hábitos de escucha.

---

## 1. Arquitectura del Sistema: Flujo de Eventos Inmutables

El historial no es una simple base de datos pasiva; es un **Pipeline de Procesamiento Secuencial** que transforma ráfagas de eventos de Android en un diario de escucha coherente.

### El Pipeline de Datos (`MusicNotificationListener.kt`)
1.  **Detección de Sesión:** El sistema escucha los callbacks de `MediaSession` y `NotificationListener`.
2.  **Captura de Snapshot:** Cada cambio genera un `MediaSnapshot`. Esta clase es **inmutable** y autocontenida:
    ```kotlin
    // MusicNotificationListener.kt
    private data class MediaSnapshot(
        val packageName: String,
        val title: String,
        val artist: String,
        // ... (metadatos)
        val firstObservedAt: Long, // Memoria de inicio
        val maxPositionMs: Long    // Récord de progreso
    )
    ```
3.  **Identidad de Doble Capa:**
    *   **Identidad de Sesión (`sessionIdentity`):** `Paquete + Título + Artista`. Se usa para mantener la continuidad del tiempo y progreso ante "refinamientos" (ej. cuando Spotify añade el álbum a los 10 segundos).
    *   **Identidad de Contenido (`trackKey`):** Incluye álbum y duración. Se usa para la precisión de portadas y letras.
4.  **Cola FIFO:** Cuando una canción termina, el snapshot se envía a un `Channel<MediaSnapshot>` (FIFO) para ser procesado en segundo plano sin bloquear el sistema.

---

## 2. Analítica de Escucha: Clasificación de 3 Bandas

Para distinguir entre un "error de paso", un "descarte" y una "escucha real", el sistema aplica una jerarquía de validación técnica en `processHistoryEvent`:

### A. Nivel 1: El Portero (Anti-Zapping)
Antes de entrar al historial, la canción debe superar los **5 segundos** de actividad.
- **Validación Híbrida:** Se considera válida si `maxPositionMs >= 5s` ( autoridad del reproductor) **O** si el tiempo de presencia real (`System.currentTimeMillis() - firstObservedAt`) es `>= 5s`.
- **Propósito:** Eliminar el ruido generado al saltar pistas rápidamente ("shuffle").

### B. Nivel 2: El Clasificador de Calidad
Una vez dentro del historial, se calcula el `progressFactor = maxPositionMs / durationMs`:

| Rango | Clasificación | Efecto Visual | Racha |
| :--- | :--- | :--- | :--- |
| **< 40%** | `SKIPPED` | Icono `fast_forward_24px` | +1 (Aumenta) |
| **40% - 85%** | `PARTIAL` | Ninguno (Zona Neutra) | **0 (Reset)** |
| **> 85%** | `COMPLETED` | Ninguno | **0 (Reset)** |

> [!IMPORTANT]
> **Defensa contra Falsos Skips:** Gracias al campo `maxPositionMs` (Marca de Agua Alta), el sistema ignora si el reproductor resetea su posición a cero al terminar. Si en algún momento llegaste al 90%, el widget recordará ese "pico" de progreso para la clasificación final.

---

## 3. Integridad de Sesión y Llaves Digitales

Para evitar que una canción muestre la portada de otra ("cross-info"), aplicamos el sistema de validación en disco:

1.  **Persistencia:** En `MusicDataStore.kt`, cada `HistoryItem` guarda su propia `artworkKey`.
2.  **Validación de Llave:** Al renderizar la fila (`HistoryItemRow`), el widget verifica que el archivo físico en `/history/art_{hash}.webp` sea coherente con la identidad almacenada.
3.  **Fallback Seguro:** Si hay una discrepancia o el archivo no existe, se muestra el placeholder `ic_music_note`, garantizando que el usuario **nunca vea información visual errónea**.

---

## 4. Unicidad y Estrategia LRU (Move-to-Top)

El historial implementa una lógica de **Least Recently Used (LRU)** en la función `addToHistory` de `MusicDataStore.kt`:
- Si escuchas una canción que ya estaba en el historial, la entrada antigua se elimina.
- La nueva entrada (con el timestamp y racha actualizada) se inserta en la posición #1.
- **Consecuencia:** El historial es una lista de las últimas 10 canciones **únicas**, reflejando siempre tu interacción más reciente con cada tema.

---

## 5. El Rastreador de Rachas (Skip Streak)

El widget tiene "memoria de rechazo" para las canciones que saltas sistemáticamente:
- **Identidad de Racha:** Basada en `Título + Artista`.
- **Persistencia:** Se guarda un mapa de las últimas 30 canciones saltadas en el `MusicDataStore`.
- **UI:** Si la racha es de 2 o más, aparece un contador numérico al lado del icono de skip (ej. "↷ 2").
- **Reset Inteligente:** La racha se limpia si escuchas al menos el 40% de la canción (Zona Neutra).

---

## 6. Almacenamiento y Archivos Clave

| Archivo / Carpeta | Propiedad / Función |
| :--- | :--- |
| `MusicDataStore.kt` | Gestiona el JSON, la unicidad LRU y el mapa de rachas (`skip_streaks`). |
| `MusicNotificationListener.kt` | Orquestador de la inteligencia. Calcula `maxPositionMs`, `isSkipped` y gestiona la cola FIFO. |
| `MusicWidget.kt` | Renderiza la UI, valida llaves digitales y muestra los indicadores de calidad. |
| `/files/history/` | Directorio de imágenes optimizadas (WebP Lossy, 120px). |

> [!NOTE]
> Este sistema es **estrictamente eficiente**: los cálculos matemáticos ocurren solo en eventos existentes, el procesamiento de imágenes corre fuera del hilo principal (`Dispatchers.Default`) y las escrituras en disco están minimizadas mediante caché de archivos.
