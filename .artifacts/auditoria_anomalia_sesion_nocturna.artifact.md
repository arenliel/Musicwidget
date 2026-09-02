# REPORTE DE AUDITORÍA — ANOMALÍA EN SESIÓN NOCTURNA (v9.0)

**SÍNTOMA:** Portada de "Present" ausente inicialmente tras unlock, reaparece tarde. Presencia de nombres de archivo con hash en los logs a pesar de la v9.

---

## 1. ANOMALÍA: EL FALLBACK CIEGO
El sistema Glance (Widget) no pudo encontrar la portada de respaldo durante el despertar del dispositivo.

### Evidencia en Logs:
- `[06:07:21] INFO : GATING: Desbloqueo detectado. Forzando reprocesamiento de sesión.`
- `[06:07:21] DEBUG: [DIAGNOSTIC] UI_DISPATCHER: Ejecutando actualización atómica de Glance...`
- **Análisis:** En este punto, Glance intenta cargar la imagen. Al fallar la ruta viva, acude al "Paso 1 del Fallback".
- **Causa Raíz:** `MusicWidget.kt` busca en `history/buffer/buf_...`. Esta carpeta fue eliminada en la v9. El widget queda ciego hasta que la FSM se estabiliza.

---

## 2. ANOMALÍA: PERSISTENCIA DEL HASH (EL FANTASMA)
A pesar de la unificación a UUID, los logs muestran que se siguen creando archivos con nombres numéricos.

### Evidencia Verbatim:
- `[17:06:16] DEBUG: [HISTORY_ART] Guardando imagen de historial en disco para: aa7a1cda... -> Path: /.../art_-1767092172.webp`
- **Análisis:** `-1767092172` es un hash. El código en la Fase A (Eager Caching) de `MusicNotificationListener.kt` está enviando el objeto equivocado al `ArtworkStorageManager`.
- **Impacto:** El `HIST_REAPER` no puede proteger estos archivos porque no reconoce el esquema de hash, borrándolos prematuramente o dejándolos huérfanos para siempre.

---

## 3. ANOMALÍA: PÉRDIDA DE SESIÓN (DOZE DEATH)
Se detectó un reset masivo de contadores durante la madrugada.

### Evidencia:
- `[01:51:32] successCount=61` (Sesión "Present" activa)
- `[03:14:02] successCount=19` (Tras reinicio silencioso)
- **Diagnóstico:** El proceso murió en Doze Mode. Al renacer, la rehidratación cargó `うたのけはい` (una sesión anterior), perdiendo el progreso de *"Present"*. Esto confirma que el estado en DataStore no es lo suficientemente frecuente o que el commit se perdió.

---

## CONCLUSIÓN Y ACCIONES
El sistema sufre de una **desincronía de Fallback**. La solución v9 blindó el archivo, pero no actualizó al "consumidor" (el Widget).

### Plan de Corrección v9.1:
1.  **Sincronizar `MusicWidget.kt`**: Redirigir la búsqueda de carátulas de respaldo a `history/art_{sessionUUID}.webp`.
2.  **Saneamiento de Fase A**: Forzar el uso de `sessionUUID` en el guardado proactivo de imágenes.
3.  **Eliminar Ruido API**: Retirar `setWidgetPreview` para mejorar el tiempo de respuesta de Glance.

> **Auditoría finalizada. El sistema requiere parche de visibilidad inmediato.**
