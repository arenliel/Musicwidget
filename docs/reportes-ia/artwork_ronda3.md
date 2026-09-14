# Auditoría — Ronda 3 (Portadas): ¿Se Pierde el Resultado Exitoso Entre Sesiones de la Misma Ráfaga?

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## WW1. ¿Qué pasa con el resultado de una resolución exitosa si `artIncoherent` es falso en la siguiente ejecución?

Ubicado en `MusicNotificationListener.kt`.

En una ráfaga de actualizaciones para la misma canción (ej. dos notificaciones seguidas):

1.  **Ráfaga 1 (Éxito):** Resuelve el bitmap, lo guarda en los archivos físicos (`ALBUM_ART_FILE`, `ALBUM_ART_RAW_FILE`), actualiza la llave en disco (`ALBUM_ART_KEY_FILE`) y en memoria (`savedArtworkKey`), e inyecta el bitmap en el diario lógico:
    ```kotlin
    2504:                                 lastLogicalSnapshot = lastLogicalSnapshot?.copy(
    2505:                                     artworkSource = ArtworkSource.Bitmap(resolvedArtwork)
    2506:                                 )
    ```
2.  **Ráfaga 2 (Continuación):** Al entrar en `processSnapshot`, la variable `lastLogicalSnapshot` (que tiene el bitmap) se sobrescribe incondicionalmente en la Stage 1:
    ```kotlin
    2213:         // ACTUALIZACIÓN DEL DIARIO LÓGICO (Cierres-3)
    2214:         lastLogicalSnapshot = rawSnapshot
    ```
    Dado que `rawSnapshot` nace siempre con `artworkSource = ArtworkSource.Placeholder` (línea 1739), **el bitmap en memoria se pierde** en este punto.

3.  **Evaluación de Condición (Línea 2394):** Como `savedArtworkKey == snapshot.artworkKey` ya es verdadero (gracias a la Ráfaga 1), el bloque de resolución se salta.

4.  **Resultado Final:**
    -   **En el Widget:** La imagen **NO** se pierde. El widget lee de `artworkKey` en el DataStore y lo compara contra `ALB_KEY_FILE` en disco. Como ambos coinciden y los archivos físicos fueron escritos por la Ráfaga 1, el widget muestra la portada correctamente.
    -   **En Memoria (`lastLogicalSnapshot`):** El bitmap **SÍ** se pierde. El objeto queda con un `ArtworkSource.Placeholder`, a pesar de que existe una versión resuelta disponible en los archivos y en la caché de Glance.

---

## WW2. ¿`memoryArtworkCache` se consulta antes de decidir que hace falta el placeholder?

**NO.**

Analizando el bloque de error/fallback en la Stage 2 (Líneas 2508 - 2515):

```kotlin
2508:                         } else if (trackChangedUI || artworkChangedUI) {
2509:                             // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
2510:                             val placeholder = getPlaceholderBitmap()
2511:                             saveBitmapToFile(placeholder, ALBUM_ART_RAW_FILE, applyPillTransform = false)
2512:                             saveBitmapToFile(placeholder, ALBUM_ART_FILE, applyPillTransform = true)
2513:                             saveTextToFile("", ALBUM_ART_KEY_FILE)
2514:                             savedArtworkKey = null
2515:                         }
```

**Confirmación:** Si la resolución síncrona falla (devuelve `null`) y hay un cambio detectado (`trackChangedUI` o `artworkChangedUI`), el sistema escribe el placeholder directamente en disco y limpia las llaves. **No hay ninguna consulta a `memoryArtworkCache` (la bóveda de reserva por título/artista) en este flujo de la Stage 2.**

*(Nota: `memoryArtworkCache` sí se consulta dentro de `findRealAlbumArt` como el último nivel de la cascada, pero si esa función falló o excedió el tiempo de 3.5s, la Stage 2 no vuelve a intentarlo ni busca en la caché antes de aplicar el placeholder).*
