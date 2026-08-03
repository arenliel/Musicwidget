# Optimización Profesional del Widget Musical (Compose + Glance)

Este plan detalla las optimizaciones para reducir el consumo de memoria, IO y CPU, mejorando la estabilidad y rapidez del widget.

## User Review Required

> [!IMPORTANT]
> Se cambiará el formato de almacenamiento de `PNG` a `WebP`. Los widgets existentes podrían mostrar un estado vacío brevemente hasta que se reproduzca la siguiente canción y se genere el nuevo archivo `.webp`.

> [!IMPORTANT]
> Se eliminará la clase duplicada `MusicWidget` de `MusicNotificationListener.kt` para resolver el error de "Redeclaration". La implementación oficial residirá en `MusicWidget.kt`.

## Proposed Changes

### 1. Sistema de Artwork y Memoria

#### [MODIFY] [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
- **Decodificación eficiente**: Implementar `decodeSampledBitmap` para cargar Bitmaps ya redimensionados (max 512px) directamente desde el stream, evitando cargar el original gigante.
- **Formato WebP**: Cambiar `album_art.png` por `album_art.webp` con compresión de calidad 85%.
- **Gestión de archivos**: Migrar a un esquema de nombres basado en hash o `artworkKey` para evitar el archivo `.key` separado si es factible, o simplificar el actual.
- **Logs de diagnóstico**: Añadir trazas detalladas para medir tiempos de decodificación, tamaños y aciertos de caché.

#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
- **Caché LRU de Bitmaps**: Implementar una caché pequeña en memoria para evitar re-decodificar el archivo `.webp` en cada ciclo de renderizado del widget.
- **Caché de iconos**: Cachear los iconos de las aplicaciones (Spotify, etc.) ya que raramente cambian.
- **Eliminación de duplicados**: Borrar la definición extra de `MusicWidget` y `MusicWidgetReceiver` que causa el error de compilación.

### 2. Persistencia y Flujo de Datos

#### [MODIFY] [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
- Asegurar que `saveMusicInfo` minimice las escrituras mediante comparaciones profundas (ya implementado, pero se revisará para mayor eficiencia).

## Verification Plan

### Automated Tests
- Ejecutar `./gradlew :app:assembleDebug` para verificar que el error de redeclaración se ha resuelto.

### Manual Verification
- **Logs**: Monitorear Logcat con el tag `MusicListener` y `MusicWidget` para verificar los tiempos de "decode" y "cache hit".
- **Memoria**: Observar el Profiler de Android Studio para asegurar que el uso de memoria es estable y bajo (especialmente el heap de Bitmaps).
- **Funcionalidad**: Probar con Spotify y YouTube Music para asegurar que el artwork y los iconos se muestran correctamente.
