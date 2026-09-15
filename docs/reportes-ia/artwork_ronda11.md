# Auditoría — Ronda 11 (Portadas): Mapa Completo del Ciclo de Vida de `sessionUUID`

**Confirmación de Git Log (HEAD):**
```
e41110d (HEAD -> master) Revert "Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI"
079837e Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI
e54d9d0 Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
```

---

## AG1. Todos los lugares donde se asigna `sessionUUID`

### 1. Generación de Identidad (Constructor de `LogicalSession`)
Ubicado en `MusicNotificationListener.kt` (Línea 175). Cada vez que se instancia una nueva sesión lógica, se genera un UUID aleatorio único.

```kotlin
169:     private data class LogicalSession(
170:         /**
171:          * Uniquely identifies one physical listening session instance, even across immediate
172:          * repeats of the identical track.
173:          */
174:         val sessionUUID: String = java.util.UUID.randomUUID().toString(),
```

### 2. Compromiso en el Historial
Ubicado en `processSnapshot` (Línea 2133). Se transfiere el UUID de la sesión al evento de commit.

```kotlin
2132:                 val commitEvent = HistoryEvent.CommitSession(
2133:                     sessionUUID = session.sessionUUID,
```

### 3. Compromiso en "Ahora Sonando" (`MusicInfo`)
Ubicado en `processSnapshot` (Línea 2572). Se asigna al objeto de información que el widget consume.

```kotlin
2552:                     val finalMusicInfo = MusicInfo(
...
2572:                         sessionUUID = session?.sessionUUID ?: "",
```

---

## AG2. ¿En qué momento exacto, dentro de `processSnapshot`, `session` pasa a apuntar a una sesión nueva?

Ubicado en `MusicNotificationListener.kt` (Línea 2182). 

Cuando se detecta un cambio de identidad (`sessionEnded = true`), se crea un objeto `newSession` y se asigna a la variable global de clase `currentLogicalSession`:

```kotlin
2182:             currentLogicalSession = newSession
```

---

## AG3. ¿Esa reasignación ocurre antes o después del bloque donde Artwork-2 leía `session?.sessionUUID`?

**OCURRE ANTES.** 

La reasignación de la variable global `currentLogicalSession` ocurre en la **Stage 1** (Línea 2182). El bloque de la **Stage 2** (donde se intentó implementar Artwork-2) comienza en la línea **2380**.

### Hallazgo Crítico de Integridad:
Aunque la variable global `currentLogicalSession` se actualiza, la función `processSnapshot` utiliza una **captura local** de la sesión al inicio de su ejecución:

```kotlin
1913:         val session = currentLogicalSession
```

Debido a que esta variable local `session` **NUNCA** se reasigna tras la creación de la nueva sesión en la línea 2182, toda la **Stage 2** (incluyendo el bloque de construcción de `finalMusicInfo` en la línea 2552) continúa utilizando la referencia a la **SESIÓN ANTERIOR (OLD)**.

---

## AG4. Durante una ráfaga de "sesiones nuevas" en menos de un segundo: ¿cuántas veces cambia `session` en ese lapso?

En el contexto de una **única ejecución** de `processSnapshot` que detecta un cambio de identidad:
- La variable global `currentLogicalSession` cambia **1 vez** (de Vieja a Nueva).
- La variable local `session` (el contexto de la función) cambia **0 veces** (permanece siendo la Vieja).

En el contexto de una **ráfaga de eventos de sistema** (debido a la lógica de debounce en `requestRefresh`):
- El sistema de debounce cancela los trabajos previos. Si una ejecución de `processSnapshot` estaba en curso y es cancelada, se detiene.
- La **última ejecución** de la ráfaga capturará el valor de `currentLogicalSession` que dejó la ejecución anterior (si esta alcanzó a llegar a la línea 2182 antes de ser cancelada).

**Resultado:** Es posible que `session` (local) apunte a la "Canción A" mientras se está procesando la "Canción B", y que la "Canción B" actualice la variable global a una nueva "Sesión B". Si entra un tercer evento para la "Canción C" antes de que el anterior termine, este capturará la "Sesión B" como su base de comparación. 

Sin embargo, el error más grave identificado es que **dentro de un mismo ciclo de cambio de canción**, los metadatos de la nueva canción (Stage 2) se están asociando al UUID de la sesión que acaba de terminar (debido al uso de la variable local capturada en la línea 1913).
