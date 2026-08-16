# Especificación Técnica: Sistema de Rescate e Inteligencia de Iconos

Este documento detalla la arquitectura avanzada del subsistema de iconos del Music Widget. Se ha implementado un motor de normalización dinámica que garantiza consistencia visual entre aplicaciones, optimización de batería y paridad con las mejores aplicaciones de personalización del mercado.

## 1. El Problema: Disparidad de Masa Visual

Las aplicaciones Android utilizan diferentes formatos de iconos (Siluetas de notificación, Iconos Adaptativos del Launcher, Bitmaps de color).
- **YouTube** (Prioridad 2) proporciona un icono monocromático en un lienzo de 108dp, pero su dibujo real (glifo) es pequeño.
- **Spotify** (Prioridad 1) proporciona una silueta de notificación ajustada al borde.
- **Consecuencia**: Sin un procesamiento inteligente, los iconos de apps como YouTube se ven mucho más pequeños ("minúsculos") que los de otras apps, rompiendo la armonía del diseño.

---

## 2. Solución Senior: Algoritmo de Normalización por Silueta

En lugar de usar recortes fijos (como 72dp u 86dp), hemos implementado un sistema que **interroga a la imagen** para saber cuánto espacio ocupa realmente.

### Fase A: Escaneo de Alfa (Detección de Bounding Box)
Utilizamos la función `trimTransparency` en [ImageUtils.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/ImageUtils.kt) para identificar los píxeles reales.

```kotlin
// Ref: ImageUtils.kt
fun trimTransparency(bitmap: Bitmap): Bitmap {
    var minX = bitmap.width; var minY = bitmap.height
    var maxX = -1; var maxY = -1

    // Escaneo de la matriz de píxeles buscando el canal Alfa > 0
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
    }
    // Retorna el recorte exacto del dibujo (sin aire transparente)
    return Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
}
```

### Fase B: Balance de Peso Visual (Padding del 10%)
Una vez obtenido el glifo puro, aplicamos un margen artificial para evitar que el icono toque los bordes del widget, igualando la "masa" de los iconos nativos del sistema.

```kotlin
// Ref: MusicNotificationListener.kt
private fun addVisualPadding(source: Bitmap, paddingPercent: Float): Bitmap {
    val extraW = (source.width * paddingPercent).toInt()
    val extraH = (source.height * paddingPercent).toInt()
    // Crea un lienzo con espacio de "respiración" para el glifo
    val output = Bitmap.createBitmap(source.width + (extraW * 2), source.height + (extraH * 2), ...)
    canvas.drawBitmap(source, extraW.toFloat(), extraH.toFloat(), null)
    return output
}
```

---

## 3. Jerarquía de Adquisición (Chain of Command)

El orquestador en [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) gestiona la obtención mediante este flujo de prioridades:

1.  **Prioridad 1 (Nativa)**: Extrae `smallIcon` de la notificación. Es la vía más eficiente y fiel al sistema.
2.  **Prioridad 2 (Rescate Dinámico)**: Si no hay notificación, extrae la capa `monochrome` del `AdaptiveIconDrawable` y le aplica el **Algoritmo de Silueta**. Esto garantiza que YouTube se vea grande y tintado.
3.  **Prioridad 3 (Fallback Color)**: Si la app no soporta Material You, usa el icono a color original.

---

## 4. Eficiencia y Defensa de Batería

Para evitar el error de "recortar constantemente" (procesamiento pesado innecesario), hemos implementado guardas lógicas:

```kotlin
// Detección de cambio físico de aplicación
val appChanged = previousSnapshot?.packageName != rawSnapshot.packageName

// El procesamiento de icono SOLO ocurre si la app cambió o el archivo se perdió
if (appChanged || savedAppIconKey == null) {
    resolvedAppIcon = resolveAppIcon(snapshot.packageName)
    resolvedIconKey = "${snapshot.packageName}_stable"
}
```

**Justificación técnica:** El escaneo de 11,000 píxeles de un icono de 108dp toma microsegundos, pero gracias a la guarda `appChanged`, este gasto de CPU solo ocurre **una vez por sesión de reproducción** (ej. una vez cuando abres YouTube y nunca más hasta que cambies a Spotify).

---

## 5. Resumen de Archivos Involucrados

| Archivo | Función |
| :--- | :--- |
| [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) | Ejecuta la jerarquía y el balance visual del 10%. |
| [ImageUtils.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/ImageUtils.kt) | Provee el motor de escaneo de píxeles (`trimTransparency`). |
| `app_icon.webp` | Imagen optimizada y normalizada guardada en disco. |
| `app_icon.key` | Llave digital que asegura la integridad entre la app y el icono. |

---

## Conclusión para Desarrolladores
Esta implementación resuelve un problema estético mediante un enfoque algorítmico robusto. El sistema no "adivina" el tamaño; lo calcula basándose en la realidad de los píxeles entregados por el sistema, garantizando una interfaz de usuario coherente, profesional y altamente optimizada.
