# Hoja de Ruta: Analítica Avanzada de Escucha e Integridad de Identidad

Este documento detalla la evolución definitiva del sistema de historial, resolviendo bugs de "Falsos Skips" mediante una arquitectura de identidad de doble capa y un sistema de rachas persistentes.

---

## 1. El Bug de "Crisis de Identidad" (Diagnóstico)

**Problema:** Muchas canciones aparecen como "Skip" aunque se escuchen completas.
**Causa Raíz:** El `trackKey` actual incluye `album` y `durationMs`. Android/Spotify a menudo envían estos datos con retardo (refinamiento). Cuando esto ocurre a mitad de canción, el widget cree que es un track nuevo y **resetea a cero** el `maxPositionMs` (watermark). Si el refinamiento ocurre cerca del final, se pierde todo el progreso acumulado y la canción se marca como skip al compararse con el valor reseteado.

---

## 2. Solución: Arquitectura de Identidad de Doble Capa

Para garantizar la continuidad del progreso, separaremos la forma en que el widget identifica una canción:

### A. Identidad de Sesión (Loose Identity)
- **Fórmula:** `packageName + title + artist`.
- **Uso:** Única fuente de verdad para heredar `maxPositionMs` y `firstObservedAt`.
- **Beneficio:** Es inmune a refinamientos tardíos de álbum o duración. El progreso no se resetea mientras la tríada principal sea la misma.

### B. Identidad de Contenido (Strict Identity)
- **Fórmula:** `trackKey` completo actual (incluye álbum/duración).
- **Uso:** Sincronización de portadas (`.key`) y letras.
- **Beneficio:** Mantiene la precisión total para activos visuales donde un cambio de álbum sí requiere una nueva imagen.

---

## 3. Clasificación de Calidad en Tres Bandas

Superamos el modelo binario para adoptar una lógica más humana basada en el **Ratio de Finalización (`maxPositionMs / durationMs`)**:

| Rango de Progreso | Clasificación | Efecto Visual | Efecto en Racha |
| :--- | :--- | :--- | :--- |
| **< 40%** | `SKIPPED` | Icono `fast_forward_24px` | Aumenta Racha (+1) |
| **40% - 85%** | `PARTIAL` | Ninguno (Zona Neutra) | **Reset de Racha a 0** |
| **> 85%** | `COMPLETED` | Ninguno | **Reset de Racha a 0** |

> [!TIP]
> **Reset en Parcial:** Si el usuario escuchó el 50%, le dio una oportunidad real al tema. No debe ser penalizado con una racha de "rechazo".

---

## 4. El Rastreador de Rachas (Skip Streak)

### Mecánica de Persistencia
1.  **Identidad de Racha:** Se basa en la **Identidad de Sesión** (`title + artist`) para funcionar entre diferentes reproductores.
2.  **Almacenamiento LRU:** Mapa persistente en `MusicDataStore` limitado a las últimas **20-30 canciones** con racha activa para optimizar memoria.
3.  **Visualización:** El icono de skip en el historial mostrará un badge numérico (ej. "↷ 2") si la racha es ≥ 2.

---

## 5. Defensa en Profundidad: El Filtro Anti-Reset

Como medida de seguridad extra frente a errores del MediaSession de Android, se implementará una guarda aritmética:

- **Lógica:** Si el reproductor reporta una caída súbita de posición (ej. de 200s a 1s) justo antes de un cambio de track, el sistema detectará esta **"caída sospechosa"** y mantendrá el valor máximo previo para el cálculo del historial.

---

## 6. Resumen de Impacto Técnico

- **CPU:** Operaciones matemáticas simples (divisiones y comparaciones `Math.max`) que ocurren solo en eventos existentes. **Impacto nulo.**
- **Batería:** Al consolidar escrituras y evitar redibujados innecesarios mediante el `uiUpdateFlow`, el consumo es óptimo.
- **Integridad:** El widget se vuelve un "esclavo inteligente" del reproductor, filtrando el ruido técnico pero respetando la intención del usuario.
