# Especificación Técnica: Indicador de Estado del Widget (Status Text)

Este documento detalla la arquitectura, lógica de negocio y flujo de datos del componente de información de estado en los widgets de Music Widget. Su propósito es servir de referencia para futuras modificaciones o depuración del sistema de "tiempo relativo" y "estado de sesión".

## 1. Arquitectura de Datos

El estado del widget se fundamenta en la clase de datos `MusicInfo` (definida en [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)), utilizando tres campos críticos:

| Campo | Tipo | Descripción |
| :--- | :--- | :--- |
| `isPlaying` | `Boolean` | Indica si el `PlaybackState` actual es `STATE_PLAYING`. |
| `isSessionActive` | `Boolean` | Indica si existe una sesión multimedia viva en el sistema. |
| `lastUpdate` | `Long` | Timestamp (ms) de la última vez que se guardó un cambio real. |
| `playbackDeviceName` | `String` | Nombre del dispositivo de salida actual (ej. "Galaxy Buds Pro"). |
| `playbackDeviceType` | `Int` | Tipo de hardware de salida (ej. `TYPE_BLUETOOTH_A2DP`). |
| `durationMs` | `Long` | Duración total de la pista (parte de la identidad de la sesión). |

## 2. Lógica de Negocio: `getStatusText`

### Filtrado Temprano de Identidad (Allow-list)
Antes de llegar a la lógica de UI, el `MusicNotificationListener` realiza una validación de "confianza" sobre el paquete emisor:
1. **Categoría de Sistema:** Apps marcadas como `AUDIO` o `VIDEO`.
2. **Servicios Multimedia:** Apps que declaran un `MediaBrowserService`.
3. **Lista Blanca Estática:** Reproductores conocidos (Spotify, Apple Music, YouTube Music, etc.).
Si una aplicación no cumple estos criterios o está en la `BLACKLIST` del usuario, el evento se descarta y no afecta ni a la UI ni al `lastUpdate`.

### Escenarios de Estado:

1.  **Escuchando:** Si `isPlaying` es true.
2.  **En pausa:** Si `isPlaying` es false pero `isSessionActive` es true.
3.  **Sesión Latente (Snapshot):** Si `isSessionActive` es false pero el DataStore mantiene metadatos. El widget muestra la última canción con el tiempo relativo de inactividad.
4.  **Vacío/Inicial:** Si no hay metadatos.

## 3. Cálculo de Tiempo Relativo: `formatRelativeTime`

Esta función traduce la diferencia de tiempo en milisegundos a una cadena legible por humanos.

- **Umbral "Hace poco":** Si la diferencia es menor a 1 hora, devuelve `R.string.widget_time_recently` ("Hace poco" o "Reciente").
- **Escala de Horas:**
    - 1 hora exacta: `R.string.widget_time_one_hour` ("— Hace 1 hora").
    - 2 a 23 horas: `R.string.widget_time_hours` ("— Hace %d horas").
- **Escala de Días:** Más de 24 horas devuelve `R.string.widget_time_days` ("— Hace días").

## 4. Ciclo de Vida del Dato (`lastUpdate`)

El campo `lastUpdate` representa la **identidad temporal de la sesión**. Su actualización es restrictiva para evitar reseteos falsos:

### Criterios de Actualización del Reloj:
- **Cambio de Identidad:** Si cambian el `title`, `artist` o `trackKey` (nueva canción).
- **Cambio de Pulso Real:**
    - Transición de `isPlaying` (Play <-> Pausa).
    - Cierre de sesión (`isSessionActive` pasa de `true` a `false`).
    - **Cambio de Dispositivo de Salida**: Si cambian el `playbackDeviceName` o el `playbackDeviceType`.

### Eventos que NO resetean el reloj:
- **Re-apertura de Sesión:** Si la sesión estaba cerrada y se reabre (`isSessionActive` de `false` a `true`) pero el estado sigue siendo **Pausado**, el reloj **no** se mueve. Esto mantiene el "Hace 3 horas" aunque el usuario abra la app para mirar su biblioteca.
- **Letras Sincronizadas:** Cambios en `currentLyric`.
- **Iconografía:** Cambios en el icono de la aplicación.

> [!IMPORTANT]
> Se ha eliminado el uso de `forceUpdate = true` en el listener. Ahora la lógica de persistencia en `MusicDataStore` es la única autoridad que decide si un cambio es lo suficientemente relevante como para resetear el timestamp de inactividad.

## 5. Implementación en la UI: Consciencia de Diseño (Layout Awareness)

El sistema está diseñado para ser inteligente según el espacio disponible, evitando redundancias visuales entre los diferentes tamaños del widget.

### Función: `TextInfo` (Prioridades de la segunda línea)

Esta función determina qué se muestra en la línea debajo del título (Artista/Letra/Tiempo). Utiliza un modelo de **prioridad excluyente** basado en el estado de la sesión y el tipo de layout:

1.  **Modo Historial (Sesión Cerrada) + Layout Compacto:**
    - **Condición:** `showRelativeTime == true` Y `isSessionActive == false`.
    - **Comportamiento:** El campo del artista se reemplaza por el tiempo relativo (ej: *"— Hace poco"*).
    - **Razón:** En widgets pequeños (Pill), este es el único espacio disponible para dar feedback de tiempo al usuario.

2.  **Modo Sesión (Reproduciendo/Pausado):**
    - **Condición:** `isSessionActive == true`.
    - **Comportamiento:** Se intenta mostrar la **Letra Sincronizada** si está disponible y la opción está activa. Si no hay letra en ese momento, se muestra el **Nombre del Artista**.
    - **Razón:** Mientras la sesión está viva, la prioridad es el contenido musical, no el tiempo de inactividad.

3.  **Modo Historial (Sesión Cerrada) + Layout Expandido:**
    - **Condición:** `showRelativeTime == false` (Widget Large).
    - **Comportamiento:** El campo inferior muestra siempre el **Nombre del Artista**.
    - **Razón:** El widget Large ya tiene una zona dedicada arriba a la derecha para el tiempo relativo (Status Text), por lo que mostrarlo abajo sería redundante.

### Resumen de visualización por Apariencia:

| Estado | Widget Standard (Pill) | Widget Large (2x4) |
| :--- | :--- | :--- |
| **Reproduciendo** | Título + Letra/Artista | Título + Letra/Artista + "Escuchando" (arriba) |
| **Pausado** | Título + Letra/Artista | Título + Letra/Artista + "En pausa" (arriba) |
| **Cerrado (<1h)** | Título + **"— Recientemente"** | Título + **Artista** + **"Reciente"** (arriba) |

> [!NOTE]
> En la apariencia **Large**, el artista es persistente. El diseño confía en el sistema de colisiones global para cambiar de layout si el espacio vertical es insuficiente, eliminando la necesidad de micro-ajustes internos de altura.

---
## 6. Localización (Strings)

Los valores literales se encuentran en `res/values/strings.xml`:
- `status_listening`: "Escuchando"
- `status_paused`: "En pausa"
- `widget_time_recently`: "Reciente"
- `widget_time_one_hour`: "— Hace 1 hora"
- `widget_time_hours`: "— Hace %d horas"

---
*Documento generado para referencia técnica del proyecto Music Widget.*
