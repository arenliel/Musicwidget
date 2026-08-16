# Hoja de Ruta: Identificación de Canciones Skippeadas en el Historial

Este documento detalla la implementación técnica para marcar visualmente las canciones que fueron saltadas rápidamente, aprovechando la infraestructura de Snapshots y el sistema de Identidad Temporal Persistente.

## 1. Lógica de Detección de Skip (Regla del 70% con Marca de Agua Alta)

La validación de "Skip" se realizará en el momento del cierre de la sesión de una canción bajo las siguientes condiciones jerárquicas:

### A. Nivel 1: El Portero (Anti-Zapping) - SIN MODIFICACIONES
La canción debe haber estado activa al menos 5 segundos (híbrido: `positionMs` o `firstObservedAt`).

### B. Nivel 2: El Clasificador (Skip) - Lógica de "Marca de Agua Alta"
Para evitar errores cuando el reproductor reinicia su posición al final, o cuando el usuario retrocede en la línea de tiempo, implementamos el concepto de **Punto Máximo Alcanzado (`maxPositionMs`)**:
- **Memoria de Progreso:** Durante toda la vida de la canción en el widget, el sistema registra el timestamp más alto reportado por el reproductor.
- **Inmunidad al Retroceso:** Si el usuario retrocede de 2:00 a 1:00, el `maxPositionMs` se queda en 2:00.
- **Cálculo Final:** Se considera **Escucha Completa** si `maxPositionMs >= 70%` de la duración.
- **Icono Skip:** Solo se muestra si el punto más lejano alcanzado nunca llegó al 70%.

### B. Uso de Recursos Existentes (Integridad)
- **`positionMs`:** Fuente primaria de autoridad. Si el reproductor dice que el usuario saltó antes del 10%, es un skip.
- **`firstObservedAt`:** Fuente de respaldo. Si `positionMs` no es confiable, usaremos `(System.currentTimeMillis() - firstObservedAt)` para verificar si el usuario realmente pasó tiempo en la canción. Si el tiempo real de presencia también es menor al 10% de la duración, se confirma el skip.
- **`durationMs`:** Requisito indispensable. Si la canción no reporta duración (ej. radio en vivo), **nunca** se marcará como skippeada por seguridad.

---

## 2. Dinámica de Actualización (Evolución del Estatus)

Gracias a la estrategia **LRU (Move-to-Top)** implementada previamente, el sistema permitirá que una canción "limpie su expediente":

1.  **Escenario 1:** Escuchas la Canción A por 15 segundos (en un tema de 3 minutos) y saltas. El historial guarda "Canción A" con el icono de skip.
2.  **Escenario 2:** Más tarde, escuchas la Canción A completa o al menos más del 10%.
3.  **Resultado:** Al procesar el nuevo historial, la lógica LRU elimina la entrada antigua (con el icono) y mueve la nueva entrada (sin icono) a la primera posición.
    *   **Efecto:** El historial siempre muestra el estatus de la **última sesión de escucha válida**.

---

## 3. Implementación Técnica

### A. Estructura de Datos (`MusicDataStore.kt`)
- Modificar `HistoryItem` para incluir `isSkipped: Boolean`.
- Actualizar la codificación/decodificación JSON (campo `"sk"`).

### B. Procesador de Historial (`MusicNotificationListener.kt`)
- En `processHistoryEvent`, realizar el cálculo matemático del 10% antes de crear el `HistoryItem`.
- Guardar el resultado en el objeto que se envía a `addToHistory`.

### C. Interfaz Visual (`MusicWidget.kt`)
- En `HistoryItemRow`, añadir el icono `R.drawable.fast_forward_24px` a la izquierda del título.
- El icono tendrá un tamaño sutil (ej. 12-14dp) y solo aparecerá si `item.isSkipped` es verdadero.

---

## 4. Casos Borde Evaluados

- **Canciones muy cortas (< 50s):** Debido al filtro anti-zapping de 5s, una canción de menos de 50 segundos nunca podrá marcarse como "Skippeada" porque al llegar al historial ya habrá superado el 10% de su duración. Esto es correcto, ya que en temas tan cortos, 5 segundos de escucha representan un interés real.
- **Cambio de App:** Si el usuario salta de Spotify a YouTube Music, el sistema captura el último snapshot de Spotify, calcula su estatus de skip y lo archiva antes de dar paso a la nueva sesión.

> [!TIP]
> Esta implementación es **atómica y eficiente**. El cálculo se realiza una sola vez por canción y no consume recursos de CPU adicionales durante la reproducción activa.
