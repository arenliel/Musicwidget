# Auditoría — Ronda 3 (Identidad): Retiro de `trackKey`, y Reintento Único de Letras por Duración Inicial

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AR1. Fórmula completa de `artworkKey` y de `artIncoherent`

Ubicado en `MusicNotificationListener.kt`.

### `artworkKey` (Líneas 515 - 521):
```kotlin
        val artworkKey: String
            get() =
                artworkUri
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
```
**Confirmación:** La duración (`durationMs`) **SÍ** participa en el cálculo de `artworkKey` cuando no hay una URI de portada disponible.

### `artIncoherent` (Línea 1998):
```kotlin
1998:         val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()
```
Puesto que `artworkKey` depende de la duración, `artIncoherent` se volverá verdadero si la duración cambia (ej. de 0ms a su valor real) y el widget es visible.

---

## AR2. ¿Qué le pasa a la Etapa 2 cuando una sesión se cierra mientras la pantalla está apagada?

Ubicado en `MusicNotificationListener.kt`.

1.  **Stage 1 (Compromiso del Historial):** Cuando se detecta que una sesión debe terminar (`sessionEnded`), el sistema emite el evento de cierre al canal de historial **antes** de evaluar la visibilidad para la Stage 2.
    ```kotlin
    2120:                 historyChannel.trySend(HistoryEvent.CommitSession(...)
    ...
    2139:                 val commitEvent = HistoryEvent.CommitSession(...
    2156:                 val res = historyChannel.trySend(commitEvent)
    ```
2.  **Gating de Stage 2 (Líneas 2337 - 2354):** Inmediatamente después de la Stage 1, si el widget no es visible, la función aborta:
    ```kotlin
    2337:         if (!isWidgetPotentiallyVisible()) {
    ...
    2353:             // Abortamos Stage 2 para evitar I/O y CPU innecesarios
    2354:             return
    2355:         }
    ```

**Confirmación:** Si la sesión se cierra con la pantalla apagada, el historial recibe la sesión (con los datos que hubiera en ese momento), pero la Etapa 2 (que realiza la resolución de alta calidad y el retoque atómico de la portada final) **NUNCA se ejecuta**. Esto significa que la última canción de una sesión nocturna podría quedar en el historial con una portada de baja calidad o un placeholder si la resolución dependía de la Stage 2.

---

## AR3. ¿Cómo sabe `songChangedForLyrics` si la duración de la canción actual estuvo "desconocida" en algún momento anterior de esta misma escucha?

No existe un flag booleano explícito llamado "startedWithUnknownDuration". Sin embargo, la información está disponible mediante la comparación de snapshots:

1.  **`birthSnapshot`:** La clase `LogicalSession` (Línea 177) conserva el snapshot exacto del momento en que nació la sesión, y es inmutable.
    ```kotlin
    177:         val birthSnapshot: MediaSnapshot, // Capturado al nacer, inmutable (v6.5)
    ```
2.  **Verificación de Duración Inicial:** Se puede determinar si la sesión empezó sin duración consultando:
    `session.birthSnapshot.durationMs <= 0L`

**Confirmación:** Aunque no hay un registro dedicado, el sistema tiene la "memoria" necesaria en `birthSnapshot` para saber si la sesión nació degradada y así permitir un reintento único de búsqueda de letras cuando `snapshot.durationMs > 0L`.
