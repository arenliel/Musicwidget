# Hoja de Ruta: Motor Técnico de Repetición (Flama e Infinito)

Este documento detalla la implementación del motor de analítica para detectar canciones en repetición (🔥) y rachas de varios días (∞), siguiendo una arquitectura de "Cero Desperdicio".

## 1. Regla de Oro: Separación de Lógica y Gráficos

> [!IMPORTANT]
> **Alcance de esta fase:** Únicamente se implementará la captura, persistencia y cálculo de los datos en el servicio y la base de datos.
> **Restricción:** El widget **no se modificará visualmente** (no se añadirán iconos ni textos) hasta recibir los lineamientos de diseño específicos del usuario.

---

## 2. Estructura de Datos de Analítica

### A. Modelo `RepeatStats`
Implementaremos una clase interna para rastrear los "hechos" del calendario:
- `playsToday`: Contador de reproducciones válidas el mismo día.
- `lastPlayedEpochDay`: El número de día en la historia (LocalDate) de la última escucha.
- `streakDays`: Contador de días consecutivos (2, 3, 4...).

### B. Persistencia (DataStore)
- Se añadirá una nueva llave `REPEAT_STATS` que almacenará un Mapa JSON.
- **Identidad:** `Hash(packageName + title + artist)`.
- **Límite LRU:** El mapa solo mantendrá las estadísticas de las últimas 30 canciones únicas para proteger la memoria.

---

## 3. Lógica de Negocio (Backend)

### A. El Disparador (Trigger)
La actualización ocurrirá en `MusicNotificationListener.kt`, dentro de `processHistoryEvent`.
- Solo se activa si la canción supera el filtro **PARTIAL** o **COMPLETED** (Autoridad del Motor Maestro).
- Las canciones marcadas como **SKIP** resetean automáticamente la racha de esa canción.

### B. El Algoritmo de Racha
1. **Mismo Día:** Si `today == lastPlayedDay`, se incrementa `playsToday`.
2. **Día Siguiente:** Si `today == lastPlayedDay + 1`, se resetea `playsToday = 1` y se suma +1 a `streakDays`.
3. **Hueco Temporal:** Si hay más de un día de diferencia, se resetea todo a los valores iniciales.

---

## 4. Prevención de Información Cruzada

Para garantizar que los datos nunca se mezclen:
1. **Identidad Atómica:** El ID incluye el `packageName`. Una canción "Intro" en Spotify nunca afectará a una canción "Intro" en YouTube Music.
2. **Transacciones Mutex:** El guardado de estadísticas ocurrirá dentro del mismo `commitMutex` del historial, asegurando que la metadata y su analítica viajen siempre juntas.

---

## 6. Políticas de Interrupción y Expiración

Para garantizar que la analítica sea verídica y no "fantasmal", aplicamos estas reglas de caducidad:

### A. El Skip como Interruptor de Racha
Si una canción tiene una racha activa (🔥 o ∞) pero el usuario realiza un **SKIP** (<40%), el sistema interpreta una ruptura de tendencia.
- **Acción:** Se resetean todos los contadores de repetición a 0 para esa canción.
- **Razón:** El historial debe reflejar la relación actual con la música; el rechazo anula la repetición.

### B. Ciclo de Vida de la Bendición (Immunity)
La inmunidad de estatus ("Canción Bendecida") está ligada estrictamente a la existencia física en el historial.
- **Límite del Historial:** 10 canciones únicas.
- **Expiración:** Si una canción sale del Top 10, pierde su escudo. La próxima vez que suene, su calidad de escucha se evaluará como si fuera la primera vez.

### C. Límites de Memoria Analítica
- **Mapa de Rachas:** Máximo 30 entradas (LRU).
- **Consistencia:** Si una canción es expulsada del mapa de rachas por falta de uso, sus contadores vuelven a 0.
