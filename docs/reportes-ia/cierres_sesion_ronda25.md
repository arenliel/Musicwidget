# Auditoría — Ronda 25: Confirmar que la Resolución de Portada Dependía de Recreación de Sesión

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## SS1. ¿Bajo qué condición se llama a `findRealAlbumArt`?

Ubicada en `MusicNotificationListener.kt`. Se llama indirectamente a través de `resolveArtworkDeduplicated` en la Stage 2 de `processSnapshot`.

### Condición de disparo (Líneas 2393 - 2394):
```kotlin
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
```

### Definición de los flags (Líneas 2361 - 2378):
```kotlin
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
...
2377:         val artworkChangedUI =
2378:             previousApplied?.artworkKey != snapshot.artworkKey
```

---

## SS2. ¿Existe algún mecanismo propio de reintento en la resolución de portada?

**NO.** Dentro de `resolveArtworkDeduplicated` y `getOrCreateArtworkDeferred` no hay bucles de reintento ni lógica de backoff.

### Código de resolución (Líneas 2862 - 2878):
```kotlin
2862:     private suspend fun getOrCreateArtworkDeferred(
...
2874:             val deferred = serviceScope.async {
2875:                 try {
2876:                     val bitmap = findRealAlbumArt(snapshot, controller, metadata)
...
2888:                     bitmap
2889:                 } catch (e: CancellationException) {
2890:                     throw e
2891:                 } catch (e: Exception) {
2892:                     Log.e(TAG, "Error resolviendo artwork", e)
2893:                     null
2894:                 } finally {
...
2901:                 }
2902:             }
```
Si `findRealAlbumArt` falla (lanza excepción) o devuelve `null`, el `Deferred` simplemente completa con `null`.

---

## SS3. ¿Qué pasa con la portada cuando una sesión "continúa" en vez de crearse nueva?

En una sesión que continúa (`FUSION`), si la resolución inicial falló:

1.  **Línea 2508:** En la ráfaga donde falló la resolución, si era un cambio de pista (`trackChangedUI`), se entra en el bloque de placeholder:
```kotlin
2508:                         } else if (trackChangedUI || artworkChangedUI) {
2509:                             // Solo usamos el placeholder si estamos seguros de que no hay arte para esta pista
...
2514:                             savedArtworkKey = null
2515:                         }
```
2.  **Ráfaga siguiente (`FUSION`):** Como `savedArtworkKey` quedó en `null`, la condición del `if` en la línea 2394 (`|| savedArtworkKey == null`) **se cumple nuevamente**, forzando un nuevo intento de `resolveArtworkDeduplicated`.

**Verificación verbatim del comportamiento de continuación:**
```kotlin
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
```
**Efecto:** Si la portada nunca se resuelve con éxito (`savedArtworkKey` sigue siendo `null`), el sistema **reintenta en cada ráfaga** de la misma canción, incluso si no hay cambio de identidad, siempre que el widget sea visible. Si la portada se resuelve una vez (éxito o placeholder con llave válida), deja de intentarlo hasta que cambie la pista o el artwork metadata.
