# Análisis Técnico: Aplicación de Integridad de Sesión al Historial de Música

Este documento analiza cómo los principios de "Integridad de Sesión" y "Llaves Digitales" detallados en [session_integrity_and_digital_keys.artifact.md](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/.artifacts/session_integrity_and_digital_keys.artifact.md) pueden fortalecer la función de historial para eliminar duplicados y discrepancias de portadas.

---

## 1. Validación de la Identidad (`trackKey`) en el Historial

El documento técnico establece el `trackKey` como la única fuente de verdad. Actualmente, el historial usa esta llave para generar nombres de archivo, pero podemos mejorar la integridad siguiendo estas acotaciones:

*   **Problema Detectado:** Guardado múltiple de la misma canción debido a cambios menores en la sesión (refinamientos).
*   **Solución basada en Integridad:** La lógica de `isMetadataRefinement` implementada recientemente es correcta bajo el principio de "Identidad de Pista". Al ignorar cambios que no alteran la tríada (Título, Artista, Paquete), protegemos el historial de entradas duplicadas generadas por el retardo de metadatos (como la carga tardía del nombre del álbum o la duración).
*   **Recomendación:** Mantener la tríada (Título|Artista|Paquete) como el núcleo de la identidad para el historial, incluso si el `trackKey` global del widget es más complejo para otras funciones.

---

## 2. Prevención de Discrepancias de Portada (Llaves Digitales)

El sistema de archivos `.key` evita que el widget mezcle información de apps distintas. Para el historial, donde tenemos múltiples imágenes, la estrategia debe ser:

*   **Vincular Imagen a Llave:** En lugar de confiar en la posición (como el antiguo sistema `history_art_0`), cada entrada del historial debe tener su propia "llave digital" persistida en el `DataStore`.
*   **Validación al Renderizar:** El widget Glance, al mostrar el historial, debería verificar que el archivo en `/history/art_{hash}.webp` existe y que su nombre coincide con el hash almacenado en el `HistoryItem`. Si hay una discrepancia, debe caer a un placeholder, evitando mostrar la portada de una canción A para la metadata de una canción B.

---

## 3. El Filtro de Confianza (`isAppAllowed`)

El historial es especialmente sensible a "ruido" de apps que no son reproductores de música (ej. navegadores, juegos).

*   **Acotación:** Debemos asegurar que **nada** entre al `historyChannel` sin haber pasado primero por `isAppAllowed`.
*   **Estado Actual:** Actualmente se verifica al inicio de `processSnapshot`. Esto es vital y debe considerarse una regla inquebrantable para evitar que el historial se llene de basura de apps en segundo plano.

---

## 4. Protección del Tiempo de Inactividad (`lastUpdate`)

El historial registra cuándo se escuchó una canción.

*   **Integridad Temporal:** Siguiendo la lógica de "Inmunidad del Reloj", el timestamp del historial debe ser el `recordedAt` original del primer snapshot válido de esa canción, no el momento en que se guarda (que ocurre cuando la canción *termina*). Esto asegura que la hora mostrada sea el inicio real de la reproducción.

---

## Hoja de Ruta Propuesta (Roadmap)

Basado en este estudio, propongo los siguientes pasos técnicos para blindar el historial:

1.  **Refuerzo de Identidad:** Asegurar que `HistoryItem` almacene el `artworkKey` original de la sesión para validaciones cruzadas.
2.  **Validación de Carga:** Modificar `HistoryItemRow` en el Widget para que realice una comprobación de integridad del archivo antes de mostrar la imagen, similar a como lo hace la vista principal con `ALB_KEY_FILE`.
3.  **Auditoría de Apps:** Revisar y expandir `isAppAllowed` para garantizar que solo sesiones multimedia "puras" generen historial.
4.  **Limpieza Basada en Llaves:** La función `cleanupHistoryFiles` debe basarse estrictamente en las llaves presentes en el `DataStore` para evitar borrar imágenes que aún son válidas pero que el sistema podría confundir por errores de nombrado.

> [!IMPORTANT]
> **Conclusión del Análisis:** La causa de los duplicados es la falta de un "Debounce de Identidad" agresivo, y la causa de las portadas cruzadas es la falta de validación de llaves en el momento del renderizado del historial. Aplicando los principios de la documentación técnica, podemos erradicar estos bugs.
