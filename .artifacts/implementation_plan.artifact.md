# Plan de Implementación — Reparación de Fallback Glance y Saneamiento Final

Este plan corrige la regresión de visibilidad de portadas en Glance y completa la eliminación de la arquitectura de buffers obsoleta.

## 1. Reparación del Pipeline de Fallback (Glance)
- Modificar `MusicWidget.kt` para que el Paso 1 del Fallback Pipeline busque en la nueva ruta v9.
- **Antes:** `history/buffer/buf_{sessionUUID}.webp`
- **Ahora:** `history/art_{sessionUUID}.webp`
- Esto permitirá que Glance recupere la carátula "de nacimiento" durante periodos de latencia o tras reinicios del proceso, evitando placeholders innecesarios.

## 2. Saneamiento de Referencias Obsoletas
- Eliminar cualquier referencia residual a la carpeta `history/buffer/` en `MusicNotificationListener.kt`.
- Asegurar que `HIST_REAPER` no intente procesar una carpeta que ya no existe.

## 3. Blindaje de Marca de Agua (Bloque D)
- Refinar el inicio de `maxPositionMs` en `LogicalSession` para asegurar que el primer paquete no degrade un récord rehidratado.

---

## Verificación Plan
- **V21 (Nueva)**: Forzar muerte del proceso mientras suena una canción, reabrir y verificar que la portada es visible instantáneamente (Fallback Paso 1).
- **V22 (Nueva)**: Verificar en Logs que no hay intentos de lectura en `history/buffer/`.
