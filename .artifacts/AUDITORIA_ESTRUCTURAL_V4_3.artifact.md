# Auditoría Estructural: Metadatos y MediaSession (v4.3)

Este reporte detalla el estado actual del flujo de metadatos y gestión de recursos multimedia en el proyecto Music Widget, con el fin de evaluar la transición hacia una arquitectura de **Eager Caching**.

---

## 1. Ciclo de Vida del Artwork (MediaMetadata)

### Punto de Extracción
La extracción nativa ocurre dentro de los callbacks de `MediaController` registrados en `updateActiveSessions`.

- **Ubicación exacta:** `MusicNotificationListener.kt`, dentro del bloque `MediaController.Callback`.
- **Flujo:** El sistema extrae el `Bitmap` o la `Uri` desde los metadatos de Android y los encapsula en el objeto sellado `ArtworkSource`.
- **Líneas relevantes:**
  ```kotlin
  // MusicNotificationListener.kt - Aproximadamente Líneas 1000-1015 (en el callback onMetadataChanged)
  val artworkSource = when {
      metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ->
          ArtworkSource.Bitmap(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
      metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) != null ->
          ArtworkSource.Uri(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
      else -> ArtworkSource.Placeholder
  }
  ```

### Permanencia en RAM
- **Persistencia Volátil:** El objeto `ArtworkSource` vive dentro del `MediaSnapshot`. Actualmente se mantiene en RAM en dos puntos: `lastObservedSnapshot` y `lastLogicalSnapshot`.
- **Destrucción:** El objeto es reemplazado (y por tanto elegible para GC) en el instante en que `processSnapshot` recibe una nueva actualización de la misma sesión o una sesión nueva toma el control.
- **Riesgo:** Si la resolución de imagen es lenta, el `Bitmap` nativo podría volverse inaccesible si el sistema operativo recicla el objeto `MediaMetadata` original antes de que el widget termine de procesarlo.

---

## 2. Evaluación del Sistema de Llaves (`trackKey`)

### Determinismo
El identificador único se genera de forma combinada basándose en metadatos básicos.

- **Definición:**
  ```kotlin
  // MusicNotificationListener.kt - Líneas 388-398
  val trackKey: String
      get() = "$title|$artist|$durationMs"
  ```
- **Uso en Archivos:** Se utiliza el `hashCode()` de este String para nombrar los archivos físicos: `art_${snapshot.trackKey.hashCode()}.webp`.
- **Hallazgo:** El uso de `durationMs` es excelente para distinguir entre diferentes versiones (remixes, en vivo) de la misma canción que comparten título y artista. Sin embargo, el `hashCode()` tiene una probabilidad estadística mínima de colisión. Para un sistema de 10 ítems (historial), es seguro.

---

## 3. Cronómetro de Reproducción (El Umbral de 5s)

### Rastreo de `durationObserved`
Actualmente, el sistema utiliza un modelo **pasivo (Reactive)**: el cronómetro no se evalúa hasta que llega el *siguiente* evento de MediaSession.

- **Ubicación:** `MusicNotificationListener.kt` - Líneas 1570-1572.
- **Lógica actual:**
  ```kotlin
  val durationObserved = System.currentTimeMillis() - previousLogical.firstObservedAt
  if (durationObserved > 5000L || finalEstimatedPos >= 5000L) { ... }
  ```
- **Evaluación de Trigger Asíncrono:**
  > [!TIP]
  > Es totalmente factible inyectar un "Watcher" asíncrono. En el momento en que se detecta un `STATE_PLAYING`, se puede lanzar una corrutina con `delay(5000)` que, al despertar, verifique si el `trackKey` sigue siendo el mismo. Si es así, realiza el "Eager Commit" del historial sin esperar a que la canción termine o se haga skip.

---

## 4. Riesgos de Rendimiento y Memoria (SGR & CPU)

### Aislamiento de Hilos (Dispatchers)
- **Hallazgo Crítico:** El procesamiento de `processHistoryEvent` (que incluye el `compress` a WebP y operaciones de File IO) se ejecuta dentro de `startHistoryProcessor` bajo `serviceScope.launch`.
- **Deficiencia:** No se observa un cambio explícito a `withContext(Dispatchers.IO)`. Esto significa que las operaciones pesadas de disco están compitiendo por tiempo de CPU en el pool de despacho por defecto, lo que podría causar micro-stutters si hay muchas ráfagas de skips.

### Gestión de Bitmaps y Leaks
- **Reciclaje:** El código actual llama correctamente a `historyPill.recycle()` tras la compresión.
- **Memory Leak Potencial:** Si el widget está en modo "Gating" (pantalla apagada), los snapshots se apilan en el `historyChannel` (capacidad 50). Si el usuario hace skips masivos con la pantalla apagada, podríamos tener hasta 50 objetos `MediaSnapshot` con referencias a `Bitmaps` en la cola del canal, consumiendo memoria RAM valiosa innecesariamente hasta que se encienda la pantalla.

---

## Conclusiones de la Auditoría
1. **Infraestructura lista:** El sistema de `trackKey` es sólido para la inmutabilidad.
2. **Cuello de botella en IO:** Urge mover la persistencia a un hilo dedicado de IO.
3. **Optimización de Memoria:** Se recomienda que si la pantalla está apagada, el snapshot en el canal **no contenga el Bitmap pesado**, sino solo la URI o referencia, delegando la carga al momento del Catch-Up.
4. **Evolución Proactiva:** El paso de "Passive Trace" a "Active Watcher" (corrutina de 5s) eliminará la latencia en el registro del historial.

render_diffs(file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
