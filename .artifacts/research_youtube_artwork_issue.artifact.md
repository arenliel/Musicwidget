# Investigación Técnica: Diagnóstico Final YouTube e Iconos Tintados

Este documento concluye la fase de diagnóstico sobre por qué YouTube no muestra carátulas y por qué sus iconos no se tintan con Material You.

## 1. Conclusiones del Diagnóstico (Agosto 7, 2026)

### A. El Problema de la Portada (YouTube Main App)
- **Hallazgo:** YouTube suprime su notificación de barra de estado cuando la app está en primer plano. Además, sus objetos `MediaMetadata` y `MediaDescription` están vacíos de imágenes o URIs para ahorrar memoria (Binder limit).
- **Veredicto:** **Irresoluble por vías estándar**. Al no haber rastro de la imagen en ningún canal del sistema (Metadatos/Extras/Notificación), no hay fuente de la que extraer la miniatura. Esto coincide con el comportamiento de otros widgets similares.

### B. El Problema del Icono Tintado
- **Hallazgo:** El sistema reporta `monochrome=true` para el icono adaptativo de YouTube.
- **Causa del fallo:** Nuestro widget depende de la notificación para obtener el icono tintable. Al no haber notificación, cae en el fallback del icono de la app a color.
- **Veredicto:** **Solucionable**. Podemos extraer la capa monocromática del icono de la aplicación directamente desde el sistema, ignorando la ausencia de notificación.

---

## 2. Hoja de Ruta de Solución (Propuesta)

### Objetivo: Paridad con la Competencia (Iconos Tintados)
Implementaremos un nuevo motor de extracción de iconos que funcione incluso sin notificación:
1. **Intento 1:** Extraer `smallIcon` de la notificación (Mantiene funcionalidad actual para Spotify/etc).
2. **Intento 2 (Rescate):** Si falla el anterior, pedir `AdaptiveIconDrawable` al `PackageManager`.
3. **Procesamiento:** Extraer `monochrome` layer si existe.
4. **Fallback:** Icono a color (Estado actual).

---

## 3. Guía de Limpieza (Rollback de Sondas)

Una vez aprobada la solución, se deben eliminar los siguientes logs de `MusicNotificationListener.kt`:
- `[DEBUG_YT]`
- `[DEBUG_YT_NOTIF]`
- `[DIAGNOSTIC] resolveAppIcon: Iniciando sonda...`
- `[SONDA]`
