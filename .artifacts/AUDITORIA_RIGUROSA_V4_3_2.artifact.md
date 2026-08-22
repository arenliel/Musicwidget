# Auditoría Rigurosa y Corrección: Historial y Persistencia (v4.3.2)

Este documento detalla las correcciones aplicadas para eliminar la condición de carrera en la persistencia de portadas del historial y asegurar la inmutabilidad de los recursos gráficos.

## 1. Auditoría de Llaves (TrackKey Matching)

Se ha verificado la consistencia de `trackKey` en los dos momentos críticos del flujo:
- **T+5s (Eager Cache):** Se genera basándose en el snapshot refinado.
- **T+Final (Commit):** Se genera basándose en el snapshot de salida.

### Hallazgo
La llave `$title|$artist|$durationMs` es determinista. Se han añadido logs para asegurar que el `hashCode()` utilizado para el nombre del archivo sea idéntico en ambos puntos.

## 2. Independencia de Archivos en Disco

Se ha implementado una política de **Aislamiento Total** para el historial.
- **Cambio:** Los ítems del historial ya no dependen de variables globales ni de archivos temporales de la sesión activa.
- **Implementación:** Se utiliza el prefijo `art_` seguido del hash de la llave única de la pista.

## 3. Snapshot Inmutable en el Commit

Al momento de realizar el `addToHistory`, el sistema ya no consulta el estado actual de la sesión (que podría pertenecer a la siguiente canción).
- **Lógica:** Se consume la ruta del archivo pre-procesada desde `eagerArtworkPaths` o se verifica la existencia física basada en la llave de la canción que *termina*.

## 4. Logs de Diagnóstico (Traza de Auditoría)

Se han inyectado los siguientes logs para monitorear la salud de la persistencia:
- `[HISTORY_ART] Guardando imagen de historial...`
- `[HISTORY_ART] Recuperando imagen de historial...`
- `[HISTORY_ART] Estado final asignado al HistoryItem...`

---

## Cambios en el Código

### [MODIFY] [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
- Refactor de `processHistoryEvent` para blindaje de metadatos.
- Inyección de logs de auditoría.
- Garantía de inmutabilidad en el paso de parámetros.

render_diffs(file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
