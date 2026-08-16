# Informe Maestro: Evolución y Refactorización del Historial de Música

Este documento detalla la transformación técnica del historial del Music Widget, desde una arquitectura simple hasta un sistema reactivo de alta precisión, cubriendo los problemas encontrados y sus soluciones definitivas.

---

## 1. El Problema de la Unicidad (Estrategia LRU)

**Objetivo:** Evitar que la misma canción aparezca varias veces en el historial si el usuario alterna entre canciones.

### Implementación Técnica
En [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt), la función `addToHistory` fue rediseñada para actuar como un caché de tipo **Least Recently Used (LRU)**:

```kotlin
// MusicDataStore.kt - addToHistory
val listWithoutDuplicate = oldHistory.filterNot {
    it.title == item.title &&
    it.artist == item.artist &&
    it.packageName == item.packageName
}
val newHistory = (listOf(item) + listWithoutDuplicate).take(10)
```

**Beneficio:** Si vuelves a escuchar una canción que ya estaba en el historial, el sistema la elimina de su posición antigua y la mueve a la cima (posición #1). Esto mantiene el historial limpio y siempre actualizado con lo más reciente.

---

## 2. Detección de "Skip" (La Regla del 70%)

**Objetivo:** Identificar visualmente las canciones que el usuario decidió saltar antes de completar su reproducción.

### La Jerarquía de Filtros
Implementamos dos niveles de validación en [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt):

1.  **Nivel 1 (Anti-Zapping):** Una canción debe superar los **5 segundos** para entrar al historial.
2.  **Nivel 2 (Clasificador):** Una vez dentro, si el progreso es menor al **70%**, se le marca con el icono `fast_forward_24px`.

---

## 3. Crisis de los "Falsos Skips" y Solución Final

### El Error Detectado
Durante las pruebas, notamos que canciones escuchadas por completo aparecían con el icono de skip. Gracias a los logs, identificamos un comportamiento errático en Spotify:

> **Log de Evidencia:**
> `00:43:43.865: Seek detectado (269337ms)` -> *Casi final*
> `00:43:44.117: Seek detectado (1466ms)` -> *¡Reset repentino a cero!*

**Causa Raíz:** Los reproductores suelen resetear su posición a `0` milisegundos justo antes de cambiar de pista para prepararse para la siguiente. El widget capturaba ese "0" y lo guardaba como si fuera el progreso final.

### Solución: Marca de Agua Alta (`maxPositionMs`)
Añadimos memoria al Snapshot para recordar el punto más lejano alcanzado. En [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt):

```kotlin
// Herencia de progreso máximo
val maxPositionMs = if (isSameSession) {
    Math.max(previousSnapshot.maxPositionMs, rawSnapshot.positionMs)
} else {
    rawSnapshot.positionMs
}
```

**Resultado:** El cálculo del 70% ahora usa el **récord de progreso** (`maxPositionMs`), ignorando si el reproductor se resetea al final o si el usuario retrocede para repetir una parte.

---

## 4. Identidad Temporal (`firstObservedAt`)

**Objetivo:** Resolver la "amnesia" del widget cuando el usuario pausa y reanuda la música.

### Implementación
Añadimos un campo que registra el momento exacto en que la canción entró por primera vez al widget. Si pausas 10 minutos y reanudas, el widget **hereda** la marca de tiempo original de hace 10 minutos.

```kotlin
// MusicNotificationListener.kt
val firstObservedAt = if (isSameSession) {
    previousSnapshot.firstObservedAt
} else {
    rawSnapshot.recordedAt
}
```

Esto garantiza que el filtro de los 5 segundos (Anti-Zapping) sea acumulativo y no se reinicie con cada interacción.

---

## 5. Errores Técnicos Superados

1.  **Fallo de Recursos (Linking Error):**
    *   *Error:* `resource attr/colorControlNormal not found` en el icono de skip.
    *   *Solución:* Se eliminó la referencia dinámica al tema del sistema en el XML del icono, ya que los widgets de Glance requieren recursos estáticos o tintes aplicados vía código.
2.  **Duplicados por Refinamiento:**
    *   *Error:* Una misma canción se guardaba dos veces (una sin nombre de álbum y otra con él).
    *   *Solución:* Implementamos `isMetadataRefinement`, que bloquea el guardado en el historial si el Título, Artista y Paquete son idénticos, permitiendo que la sesión simplemente se actualice.

---

## Resumen de Archivos Clave

| Archivo | Rol |
| :--- | :--- |
| `MusicDataStore.kt` | Cerebro del almacenamiento. Gestiona la unicidad LRU y el JSON. |
| `MusicNotificationListener.kt` | Motor de inteligencia. Calcula skips, hereda tiempos y gestiona la cola FIFO. |
| `MusicWidget.kt` | Interfaz visual. Valida llaves digitales y muestra los iconos de skip. |

> [!NOTE]
> El historial es ahora un sistema **atómico y "esclavo inteligente"** de los datos del reproductor. Respeta la navegación del usuario (adelantar/atrasar) y protege la integridad visual contra fallos de sincronización de Android.
