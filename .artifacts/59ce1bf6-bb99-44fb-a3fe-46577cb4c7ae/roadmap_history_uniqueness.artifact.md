# Hoja de Ruta: Implementación de Unicidad en el Historial (LRU Strategy)

Este documento detalla la estrategia para evitar duplicados en el historial de reproducción, asegurando que cada canción aparezca una sola vez y se mantenga en la posición de "escuchada más recientemente".

## 1. Concepto: Estrategia Move-to-Top (LRU)

En lugar de simplemente rechazar duplicados, el historial adoptará un comportamiento de **Least Recently Used (LRU)** modificado:
1.  **Detección:** Al intentar guardar una canción, se escanea toda la lista actual del historial (máx. 10 elementos).
2.  **Acción si existe:** Si la canción ya está presente, se elimina de su posición actual.
3.  **Reinserción:** Se inserta la canción en la posición #1 con el nuevo timestamp.
4.  **Preservación de Arte:** No se regenera la imagen si el `trackKey` es idéntico, reutilizando el archivo `art_{hash}.webp` existente.

## 2. Definición de Identidad para el Historial

Para evitar que una canción se considere "diferente" por cambios técnicos irrelevantes, la comparación se basará en la tríada de identidad:
- **Título**
- **Artista**
- **Paquete (App)**

Ignoraremos la `durationMs` en esta comparación específica, ya que variaciones de milisegundos entre eventos no deben generar una nueva entrada.

---

## 3. Evaluación de Impacto (Rendimiento y Batería)

He analizado los tres vectores críticos de este cambio:

### A. Uso de CPU (Computación)
- **Impacto: Despreciable.**
- **Justificación:** La lista del historial está limitada a **10 elementos**. Filtrar una lista de 10 objetos JSON en memoria toma microsegundos (<< 1ms). Esta operación ocurre solo **una vez por canción** (al terminar la reproducción), no de forma continua.

### B. Uso de Memoria y Caché (Storage)
- **Impacto: Positivo (Reducción).**
- **Justificación:** Al garantizar que no hay duplicados, el archivo JSON de preferencias (`DataStore`) será más pequeño. Además, evitamos guardar archivos de imagen redundantes en el disco para la misma canción, optimizando el uso de la memoria interna.

### C. Consumo de Batería
- **Impacto: Neutro/Mínimo.**
- **Justificación:**
    - El procesamiento ocurre en el `historyChannel` (segundo plano), que ya es asíncrono.
    - Evitamos operaciones de escritura de archivos (I/O) si detectamos que el archivo de imagen para ese `trackKey` ya existe en el directorio `/history/`. Menos escrituras en disco = menos consumo energético.

---

## 4. Cambios Técnicos en el Código

### [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
Modificar `addToHistory` para filtrar la lista antes de añadir el nuevo item:
```kotlin
val listWithoutCurrent = oldHistory.filterNot {
    it.title == item.title && it.artist == item.artist && it.packageName == item.packageName
}
val newHistory = (listOf(item) + listWithoutCurrent).take(10)
```

### [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
Reforzar `processHistoryEvent` para que no sobrescriba la imagen si el archivo `art_{hash}.webp` ya existe, ahorrando ciclos de CPU en compresión de imagen.

---

> [!IMPORTANT]
> **Conclusión de Seguridad:** Este cambio es inherentemente seguro porque opera sobre una estructura de datos muy pequeña (10 items) y solo se activa en eventos de cambio de pista, los cuales son infrecuentes desde la perspectiva del hardware.
