# Informe Técnico: Anomalías en Historial y Portadas (v4.2)

Este documento detalla los hallazgos técnicos tras la inspección de `MusicNotificationListener.kt` y `MusicDataStore.kt` respecto a la herencia incorrecta de portadas y el comportamiento del historial durante ráfagas de skips.

## BLOQUE 1: Origen del Bitmap en `processHistoryEvent`

### 1.1. Resolución de Imagen
La obtención del Bitmap para el historial se realiza mediante el objeto `artworkSource` del snapshot procesado.

- **Extracto de código exacto:**
  ```kotlin
  // MusicNotificationListener.kt - Líneas 572-576
  val bitmap = when (val source = snapshot.artworkSource) {
      is ArtworkSource.Bitmap -> source.bitmap
      is ArtworkSource.Uri -> decodeAlbumArtUri(source.uri)
      ArtworkSource.Placeholder -> null
  }
  ```

- **Origen de datos:** El sistema **no lee del archivo físico `album_art.webp`** (reservado para el widget actual). Lee directamente de `snapshot.artworkSource`, el cual reside en memoria RAM durante el relevo de snapshots. Si la imagen es válida, se genera un archivo independiente: `art_${snapshot.trackKey.hashCode()}.webp`.

- **Anomalía de Herencia (Pantalla Encendida):**
  Se ha detectado un error de lógica en la propagación de metadatos en `processSnapshot()`.
  > [!WARNING]
  > **Línea 1558:** `artworkSource = previousLogical?.artworkSource ?: rawSnapshot.artworkSource`
  >
  > **Efecto:** Si una canción nueva entra sin portada (o nula), el sistema le asigna ("hereda") la portada de la canción anterior (`previousLogical`). Al guardarse esta nueva canción en el historial, arrastra la imagen incorrecta.

---

## BLOQUE 2: Encolamiento y Persistencia de DataStore

### 2.1. Canalización de Historial
- **Tipo de Canal:** El `historyChannel` es un canal con **capacidad fija (Buffered)** de 50 elementos.
  ```kotlin
  // MusicNotificationListener.kt - Líneas 202-205
  private val historyChannel = Channel<MediaSnapshot>(
      capacity = 50,
      onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  ```

### 2.2. Lectura y Escritura
- **Integridad de Datos:** Al llamar a `addToHistory`, el sistema lee el **JSON fresco desde DataStore** utilizando `prefs[HISTORY]`. No depende del estado en RAM de la UI para la persistencia, asegurando que no se pierdan ítems previos.

- **Condiciones de Carrera (Race Condition):**
  **No existe riesgo de sobreescritura.**
  1. El `startHistoryProcessor` utiliza un bucle `for` que procesa los eventos del canal de forma **estrictamente secuencial**.
  2. `dataStore.edit` garantiza atomicidad: la siguiente tarea del historial no leerá el JSON hasta que la anterior haya terminado de escribir.

### 2.3. Anomalía en Skips Rápidos
Se ha identificado por qué algunos ítems no se registran inmediatamente:
- **Filtro de Umbral:** Existe una restricción de seguridad en el disparador del historial:
  ```kotlin
  // MusicNotificationListener.kt - Línea 1572
  if (durationObserved > 5000L || finalEstimatedPos >= 5000L) { ... }
  ```
  > [!IMPORTANT]
  > Si una canción se salta (skip) en menos de **5 segundos**, el sistema la ignora completamente para evitar saturar el historial con "zapping" accidental.

---

## Conclusiones para la Corrección
1. **Corregir Herencia:** Eliminar la propagación forzada de `artworkSource` desde `previousLogical` en `processSnapshot`.
2. **Revisar Umbral:** Considerar reducir el umbral de 5 segundos si se desea capturar skips extremadamente rápidos (aunque esto podría aumentar el ruido en el historial).

render_diffs(file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
