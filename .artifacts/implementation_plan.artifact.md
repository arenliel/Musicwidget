# Plan de Implementación — Orden de Trabajo v8 (rev. 2)

Este plan detalla la ejecución de la Orden v8 para sanear la identidad del historial, corregir la redundancia visual y blindar el arranque del sistema.

## Reglas de Oro Aplicadas
- **Regla 10**: Prohibida la construcción manual de identidades. Todo procede de `MediaSnapshot`.
- **Inmutabilidad**: Uso de `sessionUUID` para el vínculo físico con los archivos de portada.
- **Normalización**: Saneamiento Unicode NFC para evitar colisiones por codificación.

---

## Propuesta de Cambios por Bloque

### BLOQUE B — Unificación de la Clave de Identidad (Raíz)
1. **Normalización Unicode**: Actualizar la factoría de `MediaSnapshot` para aplicar `Normalizer.normalize(..., Normalizer.Form.NFC)` a título, artista y álbum.
2. **Eliminación de Interpolación**: Sustituir el string manual en `commitToHistory:801` por la propiedad `endSnapshot.trackKey` o `sessionIdentity` según el propósito.
3. **Naming por UUID**: Cambiar el patrón de nombres de archivo de historial de `art_{trackKey.hashCode()}` a `art_{sessionUUID}.webp`.
   - Sincronizar este cambio en `persistHistoryArtworkEagerly` y `commitToHistory`.
4. **Simplificación en Lectura**: En `MusicWidget.kt:502`, eliminar la recomputación del hash. Confiar en `item.artworkPath` y validar solo con `file.exists()`.
5. **Migración**: Implementar en `decodeHistory` la lógica para detectar `identitySchemaVersion` antiguo y recomputar la identidad usando la función canónica.

### BLOQUE C — Restitución del Filtro de Redundancia Visual
1. **Filtro Glance**: Actualizar `HistoryList` para comparar `item.sessionIdentity` con la identidad de la pista activa.
2. **Promoción LRU**: Modificar `addToHistory` en `MusicDataStore.kt` para que, si detecta una coincidencia de `sessionIdentity`, actualice la entrada existente (timestamp, clasificación, ruta de arte) y la mueva a la cima, en lugar de crear una nueva.
3. **Orden**: Asegurar ordenación por `timestamp` descendente.

### BLOQUE A — Orden de Arranque (Incidente M)
1. **Reordenación**: Mover la llamada a `updateActiveSessions` dentro del `launch` de rehidratación en `onListenerConnected`.
2. **bootGate**: Implementar `CompletableDeferred<Unit>` para pausar el procesamiento de paquetes vivos hasta que la rehidratación termine (timeout 2s).
3. **Instrumentación**: Añadir logs `HIST_BOOT` con timestamps de arranque, apertura de compuerta y primer paquete.

### BLOQUE D — Marca de Agua (Regresión)
1. **Auditoría**: Verificar si `maxPositionMs` se está actualizando correctamente en cada tick de la FSM.
2. **Implementación**: Asegurar que el clasificador en `commitToHistory` use el máximo histórico alcanzado, no la posición final.
3. **Declaración R9**: Mapear responsabilidades de la antigua `calculateEffectiveProgress`.

### BLOQUE E — Completar Bloque 7 de la v7
1. **HIST_POISON**: Instalar el detector en el Shadow Observer para alertar sobre discrepancias entre `birthSnapshot` y el commit final.
2. **Cápsula de Commit**: Asegurar que incluya `album`, `durationMs` y `classification`.

### BLOQUE F — Camino de Refresco y Menores
1. **Auditoría API**: Investigar por qué se llama a `setWidgetPreview` en el flujo de actualización del historial y sustituir por la API de actualización de instancia si es incorrecto.
2. **Blindaje de Duración**: Ajustar la cascada para tratar `durationMs <= 0` como valor inválido (Pixel Player fix).
3. **Unificación de Timers**: Documentar y unificar `ARTWORK_TIMEOUT_MS` y el timer de promoción a UI.

---

## Plan de Verificación

### Pruebas Manuales Obligatorias
- **V6 (Reboot)**: El `sessionUUID` de "Magnet" debe ser idéntico tras reiniciar.
- **V7 (Rebobinado)**: Reproducir 90%, volver a 0%, terminar -> Debe ser **COMPLETED**.
- **V10 (Unicode)**: Probar con "ángel" y verificar portada visible.
- **V11-V13 (Redundancia)**: Verificar que la canción activa desaparece del historial y se promueve al terminar sin duplicarse.

### Verificación de Extrapolación
Capturar log `FSM_GUARD` con pantalla apagada por >10 min. La diferencia entre `rawPos` y `projectedPos` debe ser de varios segundos/minutos.
