# REPORTE DE AUDITORÍA TOTAL — SUBSISTEMA DE PORTADAS (v9.2)

Este documento contiene los hallazgos detallados de la auditoría del sistema de identidad y persistencia tras la consolidación de la Build `4244429`.

---

## BLOQUE 0 — TRAZABILIDAD DE BUILD
Se certifica el cambio de SHA tras la consolidación de los hotfixes de nombrado.

- **BUILD_ID Pre-commit:** `sha=0e7a35f built=1788096590000`
- **BUILD_ID Post-commit:** `sha=4244429 built=1788220011000`
- **Veredicto:** El SHA ha cambiado exitosamente, garantizando que el código auditado es el vigente en el repositorio.

---

## BLOQUE 1 — AUDITORÍA DE RUTAS Y NOMBRADO
Mapeo exhaustivo de todos los puntos de interacción con archivos de carátulas en el historial.

| Archivo:Línea | Tipo | Esquema | Observación |
| :--- | :--- | :--- | :--- |
| `MusicWidget.kt:249` | **LECTURA (Fallback)** | `history/buffer/buf_...` | **ANOMALÍA:** Referencia a carpeta obsoleta eliminada en v9. Causa de los placeholders tras Doze. |
| `MusicWidget.kt:502` | **LECTURA (Fila)** | `HistoryItem.artworkPath` | **CORRECTO.** Usa la ruta absoluta persistida. |
| `ArtworkStorageManager.kt:28` | **ESCRITURA** | `art_$identifier.webp` | **CORRECTO.** Saneado (sin `.hashCode()`). |
| `MusicNotificationListener.kt:777` | **COMMIT** | `art_${sessionUUID}.webp` | **CORRECTO.** Sincronizado. |
| `MusicNotificationListener.kt:966` | **EAGER (Fase A)** | `art_${sessionUUID}.webp` | **CORRECTO.** Sincronizado. |
| `MusicNotificationListener.kt:1052` | **CATCH-UP** | `art_${sessionUUID}.webp` | **CORRECTO.** Saneado en v9.1. |
| `MusicNotificationListener.kt:909` | **REAPER** | `art_{uuid}.webp` | **CORRECTO.** Extrae el UUID para comparación. |

---

## BLOQUE 2 — AUDITORÍA: PERSISTENCIA EN FASE A (EAGER)
Se analizó por qué los logs mostraban persistencia de hashes a pesar de las correcciones iniciales.

- **Código Identificado (`L966`):** El servicio enviaba el `sessionUUID` correcto, pero el `ArtworkStorageManager` aplicaba un `.hashCode()` interno por contrato heredado.
- **Acción Correctora:** Se eliminó la transformación de hash en el gestor físico. Ahora el nombre en disco es idéntico al identificador lógico (`UUID`).

---

## BLOQUE 3 — AUDITORÍA: DOZE DEATH Y REHIDRATACIÓN
Análisis de la caída del `successCount` (61 -> 19) durante la sesión nocturna.

- **Frecuencia de Persistencia:** Guardado en cada cambio de estado y guardado anticipado al apagar la pantalla.
- **Error de Constructor (`L2307`):** Se detectó que el guardado anticipado crea un objeto `MusicInfo` omitiendo el campo `album`, lo que rompe la integridad del `trackKey` al rehidratar.
- **Hipótesis del Retroceso:** La discrepancia de identidad al boot provoca el descarte de la sesión activa y la carga de un estado previo coherente.

---

## BLOQUE 4 — IMPLEMENTACIÓN AUTORIZADA
**Tarea 4.1:** Sincronización del Fallback de Glance.

- **Cambio:** Redirigir el "Paso 1" del Fallback Pipeline en `MusicWidget.kt` para buscar en la ruta definitiva: `history/art_{sessionUUID}.webp`.
- **Efecto esperado:** Eliminación de los placeholders temporales tras el desbloqueo del dispositivo.

---
**FIN DEL REPORTE V9.2.**
