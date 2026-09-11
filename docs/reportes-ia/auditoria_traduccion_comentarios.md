# Auditoría — Tamaño Real de la Tarea de Traducción de Comentarios (Fase 2)

---

### Pregunta 1 — Inventario de Archivos `.kt` y su Extensión

| Archivo | Líneas Totales |
| :--- | :--- |
| **`MusicNotificationListener.kt`** | **3214** |
| **`MusicDataStore.kt`** | **1212** |
| **`MusicWidget.kt`** | **914** |
| `SettingsComponents.kt` | 318 |
| `ImageUtils.kt` | 223 |
| `WidgetConfigActivity.kt` | 211 |
| `MainActivity.kt` | 202 |
| `PermissionUtils.kt` | 167 |
| `ArtworkDetailActivity.kt` | 151 |
| `MusicStateProvider.kt` | 136 |
| `LyricsRepository.kt` | 122 |
| `InternalLogger.kt` | 118 |
| `PermissionsTrampolineActivity.kt` | 85 |
| `CollisionSensor.kt` | 80 |
| `ArtworkStorageManager.kt` | 61 |
| `Theme.kt` | 58 |
| `MusicWidgetSandbox.kt` | 38 |
| `Type.kt` | 34 |
| `LyricsDatabase.kt` | 28 |
| `LyricsDao.kt` | 21 |
| `LyricsEntity.kt` | 14 |
| `Color.kt` | 11 |

---

### Pregunta 2 — Heurística de Comentarios en Español

Se utilizó una búsqueda de palabras comunes ("que", "para", "esto", "así") y caracteres acentuados en líneas de comentario.

| Archivo | Líneas de Comentario (ES) |
| :--- | :--- |
| **`MusicNotificationListener.kt`** | **67** |
| **`MusicDataStore.kt`** | **27** |
| **`MusicWidget.kt`** | **14** |
| `ImageUtils.kt` | 9 |
| `CollisionSensor.kt` | 4 |
| `MusicStateProvider.kt` | 3 |
| `InternalLogger.kt` | 3 |
| `ArtworkStorageManager.kt` | 2 |
| `PermissionsTrampolineActivity.kt` | 2 |
| `PermissionUtils.kt` | 2 |
| `LyricsEntity.kt` | 1 |
| `LyricsRepository.kt` | 1 |

*Nota: Otros archivos tienen 0 o comentarios que no activaron la heurística (pueden tener palabras técnicas o ser muy cortos).*

---

### Pregunta 3 — Funciones Largas con Escasa Documentación

Se identifican los siguientes bloques de más de 30 líneas que carecen de comentarios explicativos suficientes o están totalmente sin comentar:

1.  **`MusicNotificationListener.kt`**:
    *   `updateActiveSessions` (~60 líneas): Sin comentarios internos.
    *   `refreshBestSession` (~70 líneas): Muy pocos comentarios para su complejidad.
    *   `onPlaybackStateChanged` (interno al objeto anónimo): Lógica de Seek mezclada con refresh.
2.  **`MusicWidget.kt`**:
    *   `Layout4x4` (~45 líneas): Estructura de Compose sin guías de diseño.
    *   `provideGlance` (~85 líneas): Lógica de carga de estado y bitmaps con poca documentación.
    *   `MusicWidgetUIWithMock` (~40 líneas): Mocking de datos sin explicar los campos.
3.  **`MusicDataStore.kt`**:
    *   Varios bloques de `Preferences.Key` inicializados sin documentar qué representan en el widget.
    *   `toDisplayedState` (~25 líneas): Lógica de transformación de strings sin explicar casos de borde.
4.  **`SettingsComponents.kt`**:
    *   Varios componentes de UI de Material 3 con lógica de estado interna sin documentar.

---

**Conclusión del Dimensionamiento**: El núcleo del trabajo reside en los tres archivos principales. `MusicNotificationListener.kt` es, por mucho, el más crítico tanto por volumen de comentarios existentes en español como por lógica compleja sin documentar. Se recomienda dividir la Fase 2 en tandas por archivo, priorizando este último.
