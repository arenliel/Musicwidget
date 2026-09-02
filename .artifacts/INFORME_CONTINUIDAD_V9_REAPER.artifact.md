# PROYECTO MUSIC WIDGET — SANEAMIENTO DE HISTORIAL E IDENTIDAD (v9.0-Alpha)

## 1. Contexto General y Propósito
Este proyecto se centra en la refactorización profunda del pipeline de historial y la lógica de identidad de la aplicación Music Widget. Tras detectar fugas de eventos, desincronía de carátulas e inconsistencias tras el reinicio del dispositivo (Incidentes K, L y M), se procedió a una reestructuración de las capas de negocio (FSM) y almacenamiento (DataStore).

El objetivo principal es lograr un sistema **determinista e inmutable** donde cada reproducción sea trazable mediante un UUID único, eliminando la redundancia visual y garantizando la persistencia del arte incluso en condiciones extremas de latencia o bajo consumo (Doze Mode).

## 2. Decisiones Clave y Acuerdos Técnicos
- **Regla 10 (Identidad Canónica):** Prohibida la construcción de identidades (`trackKey`, `sessionIdentity`) mediante interpolación manual de cadenas fuera de los métodos centralizados.
- **Regla 11 (Propiedad de Datos):** El `sessionUUID` pertenece exclusivamente a la `LogicalSession`. Los Snapshots son anónimos; si un consumidor requiere el UUID, se le pasa como parámetro.
- **Normalización Unicode (NFC):** Todos los metadatos (título, artista, álbum) se normalizan a NFC para evitar discrepancias de hash por caracteres acentuados.
- **Naming por UUID:** Las carátulas del historial se nombran `art_{sessionUUID}.webp`. Se descartó el uso de hashes numéricos por su fragilidad ante cambios mínimos en los metadatos.
- **Oráculo de Tiempo Puro:** `projectedPositionMs()` es ahora una función matemática pura (`position + elapsed`). La marca de agua (`maxPositionMs`) reside únicamente en la sesión lógica.
- **HIST_REAPER:** Implementación de un recolector de basura por referencia que borra archivos de imagen no vinculados al historial vigente ni a la sesión activa.

## 3. Estado Actual y Avances
El sistema se encuentra en la versión **sha=0e7a35f** (built=1788096590000). Se han consolidado las Órdenes v8 y v9.

### Avances Críticos:
- **Unificación de Contratos:** Se eliminó el uso de `.hashCode()` en `ArtworkStorageManager.kt`, sincronizando la capa física con la lógica del UUID.
- **Filtro de Redundancia:** Glance ahora filtra exitosamente la canción activa del historial comparando `sessionIdentity`.
- **bootGate:** Compuerta de sincronización operativa que pausa el procesamiento de Spotify hasta que la rehidratación termina (timeout 2s), garantizando la estabilidad del UUID tras un reboot.
- **Código Funcional (Sede Única de Identidad):**
```kotlin
// MusicDataStore.kt
val sessionIdentity: String
    get() = MusicDataStore.computeSessionIdentity(packageName, title, artist)

// MusicNotificationListener.kt
val trackKey: String
    get() = "$sessionIdentity|${MusicDataStore.normalize(album)}|$durationMs"
```

## 4. Obstáculos, Errores Detectados o Puntos Abiertos
- **Error de Naming (Recién Corregido):** Se detectó que `ArtworkStorageManager` aplicaba un hash al UUID, mientras que el widget buscaba el UUID literal. Esto causó la pérdida temporal de visibilidad de portadas en la Build v9 inicial. Ya ha sido reparado.
- **Latencia de Glance:** Se detectó `rate-limiting` por parte del sistema operativo al actualizar el widget en ráfagas. Se introdujo una llamada a `updateAll` tras el commit de historial para mitigar la latencia percibida.
- **Punto Abierto (Reseteo de Rachas):** Existe una constante `GLOBAL_SKIP_STREAK_RESET_ENABLED` preparada para limpiar los skips falsos acumulados durante el Incidente K, pero está desactivada por defecto esperando confirmación.

## 5. Próxima Tarea Pendiente
**Ejecución Completa del Bloque G (Validación Final):**
Reanudar el protocolo de pruebas V7-bis a V20 en el dispositivo real para certificar la estabilidad de la Build `0e7a35f`. Especial atención a:
1. **V18/V19:** Verificar la efectividad del `HIST_REAPER` (recolección de portadas antiguas).
2. **V16:** Confirmar que las canciones en pausa prolongada ("hotline.") mantienen su portada.
3. **V20:** Validar que el `bootGate` y el orden de evaluación en `processSnapshot` eliminan los skips falsos al arranque.

---
**INSTRUCCIÓN PARA CLAUDE / NUEVO ENTORNO:**
"Entendido, tengo todo el contexto. El SHA actual es `0e7a35f`. La desincronía de carátulas por el hash en `ArtworkStorageManager` ha sido resuelta. Procedo a supervisar la validación del Bloque G (V7-bis a V20) y a monitorizar los logs de `HIST_REAPER` y `HIST_POISON`."
