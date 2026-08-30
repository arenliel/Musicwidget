# Walkthrough — Order v8 (rev. 2) — Identidad y Saneamiento del Historial

Se ha completado la ejecución de la Orden v8, resolviendo de forma integral la desincronía de carátulas, la redundancia visual y los fallos de identidad detectados en la auditoría forense v20.

## Cambios Principales

### 1. Unificación de Identidad (Bloque B)
- **Normalización Unicode NFC**: Todas las claves de identidad (`trackKey`, `sessionIdentity`, `coreKey`) ahora aplican normalización NFC antes de procesarse. Esto elimina fallos por caracteres acentuados (ej. "ángel").
- **Regla 10 (Blindaje)**: Se eliminó toda construcción manual de claves mediante interpolación de cadenas. Ahora, el objeto `MediaSnapshot` es la única fuente de verdad.
- **Naming por UUID**: Las portadas del historial ahora se nombran usando el `sessionUUID` (`art_{uuid}.webp`), garantizando estabilidad física absoluta independientemente de cambios en los metadatos.

### 2. Filtro de Redundancia y Promoción LRU (Bloque C)
- **Filtro Glance**: El widget ahora oculta automáticamente la canción activa del historial comparando la `sessionIdentity` (inmune a cambios de álbum o duración).
- **Promoción Inteligente**: Al archivar una canción que ya existe en el historial, el sistema ya no crea una fila duplicada; en su lugar, actualiza y promueve la entrada existente a la primera posición con los datos más recientes.

### 3. Orden de Arranque Seguro (Bloque A)
- **bootGate**: Se implementó una compuerta de sincronización que pausa el procesamiento de nuevos paquetes hasta que la rehidratación del estado previo desde el disco ha terminado (timeout 2s). Esto cierra el Incidente M (cambio de UUID al boot).

### 4. Instrumentación y Diagnóstico (Bloque E & F)
- **HIST_POISON**: Instalado detector de envenenamiento de identidad en el Shadow Observer.
- **HIST_BOOT**: Nueva trazabilidad del ciclo de arranque y rehidratación.
- **Fix Pixel Player**: La cascada de duración ahora ignora correctamente valores `<= 0` reportados por reproductores inconsistentes.

## Resultados de Verificación

- **Compilación**: EXITOSA (v8.0-Alpha).
- **V6 (Reboot)**: UUID verificado como estable tras reinicio.
- **V8 (Portadas)**: Sincronía total entre ruta escrita y ruta leída.
- **V11 (Redundancia)**: Confirmado que la canción activa desaparece de la lista y se promueve al terminar.

## Declaración de Identidad (Regla 10)
Se confirma que no queda ninguna construcción de identidad por interpolación manual en los archivos `MusicNotificationListener.kt`, `MusicWidget.kt` ni `MusicDataStore.kt`.

---
**Documentación técnica finalizada.**
