# Estrategia de Rescate: Solución para YouTube y Tintado de Iconos

Tras analizar los logs, hemos identificado que YouTube no entrega imágenes por los canales estándar. Este documento detalla las estrategias de "limpieza" y rescate para obtener la información necesaria.

## 1. El Problema de la Notificación "Invisible"

Nuestros logs muestran que `resolveAppIcon` falla porque no encuentra la notificación.
**Hipótesis:** YouTube asocia la notificación al `MediaSession`, pero el paquete o el ID de la notificación puede no ser trivial.

### Sonda: Búsqueda por Token de Sesión
Modificaremos la búsqueda para que no dependa del `packageName`, sino del `sessionToken`:
```kotlin
val targetToken = controller.sessionToken
val mediaNotif = notifications.firstOrNull { sbn ->
    val token = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
    token == targetToken
}
```

## 2. Miniaturas de YouTube (Metadatos vs Notificación)

Como YouTube no envía Bitmaps en `MediaMetadata`, debemos ser más agresivos en la extracción de la notificación.

### Sonda: Extracción de MediaDescription
A veces, el controlador tiene una "Descripción" que es una versión simplificada pero más fiable de los metadatos:
```kotlin
val description = controller.metadata?.description
val bitmap = description?.iconBitmap
val uri = description?.iconUri
```

## 3. Icono Tintado (Material You) sin Notificación

Si la notificación no existe (app en primer plano), el icono tintado falla. La competencia probablemente usa el icono de la app pero extraído como `AdaptiveIcon`.

### Estrategia: Extracción de Capa Monocromática
Si `resolveAppIcon` falla desde la notificación:
1. Obtener `AdaptiveIconDrawable` desde el `PackageManager`.
2. Buscar la capa `getMonochrome()`.
3. Si existe, usarla (permite tintado).
4. Si no, caer en el fallback actual (icono a color).

---

## Próximos Pasos de Diagnóstico

Aplicaremos un set de logs de "Búsqueda Exhaustiva" para ver:
1. ¿Aparece alguna notificación de YouTube si buscamos por Token en lugar de por nombre?
2. ¿Qué contiene el `controller.metadata.description`?
3. ¿Qué clase de Drawable nos devuelve el sistema para el icono de YouTube?
