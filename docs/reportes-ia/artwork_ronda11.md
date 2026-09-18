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
172:          * repeats of the identical track. Used to distinguish a genuine repeat play from a
173:          * duplicate/burst signal of the same underlying event (see the idempotency shield).
174:          */
175:         val sessionUUID: String = java.util.UUID.randomUUID().toString(),
```

### 2. Compromiso en el Historial (Retroactivo y de Cierre)
Ubicado en `processSnapshot` (Líneas 2114 y 2133). Se transfiere el UUID de la sesión al evento de commit para el historial.

```kotlin
2113:                 historyChannel.trySend(HistoryEvent.CommitSession(
2114:                     sessionUUID = session.sessionUUID,
...
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

### 4. Persistencia en DataStore
Ubicado en `MusicDataStore.kt` (Línea 486).

```kotlin
486:             prefs[SESSION_UUID] = info.sessionUUID
```

---

## AG2. ¿En qué momento exacto, dentro de `processSnapshot`, `session` pasa a apuntar a una sesión nueva?

Ubicado en `MusicNotificationListener.kt` (Línea 2182). 

Cuando se detecta un cambio de identidad (`sessionEnded = true`), se crea un objeto `newSession` (Línea 2173) y se asigna a la variable global de clase `currentLogicalSession`:

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

Debido a que esta variable local `session` **NUNCA** se reasigna tras la creación de la nueva sesión en la línea 2182, toda la **Stage 2** (incluyendo el bloque de construcción de `finalMusicInfo` en la línea 2552) continúa utilizando la referencia a la **SESIÓN ANTERIOR (OLD)** en lugar de la nueva.

---

## AG4. Durante una ráfaga de "sesiones nuevas" en menos de un segundo: ¿cuántas veces cambia `session` en ese lapso?

En el contexto de una **única ejecución** de `processSnapshot` que detecta un cambio de identidad:
- La variable global `currentLogicalSession` cambia **1 vez** (de Vieja a Nueva).
- La variable local `session` (el contexto de la función) cambia **0 veces** (permanece siendo la Vieja).

En el contexto de una **ráfaga de eventos** (gracias al debounce):
- Es posible que `session` (local) apunte a una canción mientras `currentLogicalSession` (global) ya apunta a la siguiente si una ejecución previa no fue cancelada a tiempo o si se procesaron varios eventos legítimos.
- Si entra un evento para la "Canción C" mientras se procesa la "Canción B", este capturará la sesión de la "Canción B" (si ya se actualizó el global).

---

## AG5. ¿El archivo de portada del historial, guardado al nacer una sesión, es accesible desde el punto donde se construye "ahora sonando"?

**SÍ, pero con condiciones.**

Al nacer una sesión (Línea 2185), se guarda un archivo en `history/art_${uuid}.webp`.
En el bloque de construcción de `finalMusicInfo` (Línea 2552), se tiene acceso a:
1. `sessionUUID` (que como vimos en AG3, actualmente apunta al UUID de la sesión vieja).
2. Si se corrigiera el uso de la variable local para que apunte a `currentLogicalSession?.sessionUUID`, entonces se podría reconstruir la ruta del archivo:
   `val historyFile = File(filesDir, "history/art_${currentLogicalSession?.sessionUUID}.webp")`

**Conclusión:** El archivo ya existe y es accesible, pero la lógica actual de `processSnapshot` tiene un error de ámbito (scope) que hace que la Stage 2 vea la sesión que acaba de terminar en lugar de la que acaba de empezar.
