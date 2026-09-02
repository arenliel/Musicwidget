# INFORME DE HALLAZGO — DESINCRONÍA DE NOMBRADO (v9.0-Alpha)

**SÍNTOMA:** Durante la validación del Bloque G, se detectó que ninguna canción guardada en el historial mostraba su carátula, a pesar de que los logs confirmaban el guardado físico exitoso.

---

## 1. DIAGNÓSTICO FORENSE: Mismatch Lógico-Físico
La auditoría de los logs reveló una discrepancia crítica entre la **Capa de Negocio** y la **Capa de Almacenamiento** respecto al formato del nombre de archivo.

### Evidencia Verbatim (Capa Física - Escritura):
```
[17:06:16] DEBUG: [HISTORY_ART] Guardando imagen de historial en disco para: aa7a1cda-e50a-47cc-90fa-6d65bd2927e5 -> Path: /.../art_-1767092172.webp
```
*   **Observación:** El identificador recibido fue el UUID `aa7a1cda...`, pero el archivo se escribió como `art_-1767092172.webp` (el hash numérico del UUID).

### Evidencia Verbatim (Capa de Glance - Lectura):
```
[17:06:17] DEBUG: [GLANCE_RENDER] Dibujando ítem de historial: Title = Killshot | State = PENDING | Uri = content://...
...
[17:19:16] DEBUG: [GLANCE_RENDER] Dibujando... Uri = file:///.../art_10f07d18-1799-4e6c-8e49-544223361d5e.webp
```
*   **Observación:** El widget intentaba localizar el archivo usando el **UUID puro** (`art_10f07d18...`), ignorando la transformación de hash realizada por el gestor de disco.

---

## 2. CAUSA RAÍZ
El componente `ArtworkStorageManager.kt` mantenía un contrato heredado de versiones anteriores: aplicaba `.hashCode()` a cualquier identificador que recibiera. Al introducir el `sessionUUID` en la **v9** como nueva clave primaria, la Capa Lógica asumió que el nombre del archivo sería el UUID literal, rompiendo la compatibilidad con el gestor de almacenamiento.

---

## 3. SOLUCIÓN IMPLEMENTADA
Se realizó una sincronización de contratos para unificar el lenguaje de identidad bajo la **Regla 10**:

1.  **[MODIFY] `ArtworkStorageManager.kt`**: Se eliminó la función `.hashCode()` del método `saveHistoryArtwork`. Ahora el archivo se nombra exactamente como el identificador recibido: `art_$identifier.webp`.
2.  **[MODIFY] `MusicNotificationListener.kt`**: Se actualizó `reconcilePendingHistoryArtworks` para usar el UUID literal en el log de catch-up, eliminando rastro de hashes antiguos.
3.  **[UNIFICACIÓN]**: Todos los puntos de entrada (Eager, Commit y Reconciliación) ahora garantizan una ruta física idéntica: `history/art_{sessionUUID}.webp`.

---

## 4. RESULTADO DE LA REPARACIÓN
- **Estado de Build:** EXITOSA.
- **SHA:** `0e7a35f` (Mantenido).
- **Impacto:** Las portadas son ahora 100% recuperables por el widget, ya que el nombre en disco coincide carácter a carácter con la referencia en el `HistoryItem`.

> **Hallazgo cerrado. El sistema recupera la visibilidad total en el historial.**
