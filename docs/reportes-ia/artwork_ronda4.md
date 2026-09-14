# Auditoría — Ronda 4 (Portadas): ¿La Resolución se Dispara Solo Cuando Hace Falta?

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## XX1. ¿Qué condición envuelve la llamada a `findRealAlbumArt`?

Ubicado en `MusicNotificationListener.kt`. La llamada a `findRealAlbumArt` ocurre dentro de un pipeline de resolución síncrona en la Stage 2 de `processSnapshot`.

### Condición de entrada al bloque de resolución (Líneas 2393 - 2394):
```kotlin
2393:             if (controller != null && metadata != null && 
2394:                 (trackChangedUI || artworkChangedUI || savedArtworkKey == null)) {
```

### Definición de los flags involucrados (Líneas 2361 - 2378):
```kotlin
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
...
2377:         val artworkChangedUI =
2378:             previousApplied?.artworkKey != snapshot.artworkKey
```

**Análisis de dependencia:**
- **`artIncoherent`:** La variable `artIncoherent` (calculada en la Stage 1, línea 1991) **NO** forma parte de la condición que decide si llamar a la resolución de portada. Su función es evitar la deduplicación temprana en la Stage 1 y forzar el guardado en disco al final de la Stage 2.
- **`artworkChangedUI`:** Esta es la condición principal basada en cambios de metadatos. Gracias al Conjunto Cierres-4, ahora compara contra `lastLogicalSnapshot` (vía `previousApplied`), permitiendo detectar cambios incluso si `lastAppliedSnapshot` ya se actualizó.
- **`savedArtworkKey == null`:** Este es el mecanismo de reintento "por fallo previo". Si una ráfaga anterior no logró resolver la portada, `savedArtworkKey` queda en `null`, lo que fuerza que **todas las ráfagas siguientes** de la misma canción vuelvan a llamar a la resolución mientras el widget sea visible.

**Conclusión:** No se llama incondicionalmente cada vez que la Etapa 2 se ejecuta. Depende de que haya un cambio detectado en la identidad/portada o de que no exista ninguna portada válida guardada para la sesión actual.
