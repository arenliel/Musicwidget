# Verificación — Ronda 4: Lectura Directa del Resultado (Conjunto Cierres-1b)

**Confirmación de Git Log:**
```
741c0ae (HEAD -> master) Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
2d4125d Conjunto Letras-3: Identidad de Negocio en la Re-búsqueda de Letra
```

---

## U1. Ubicación actual de ambas variables

```
C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt (147KB):
  line 1970: val previousLogical =
  line 1973: val previousReliable =
```

---

## U2. Lectura directa del bloque de declaración

(Líneas 1967-1976 de `MusicNotificationListener.kt`):

```kotlin
        // REGLA: Usamos lastAppliedSnapshot para la deduplicación de negocio (Cierres-1)
        // Esto permite que el historial use la fuente de verdad del último estado aplicado.
        val previousLogical =
            lastLogicalSnapshot

        val previousReliable =
            lastAppliedSnapshot
```

---

## U3. Lectura directa de las tres líneas que consumen estas variables

### 1. `previousLogical?.artworkKey` (Línea 1986):
```kotlin
        val trackContentChanged = previousLogical?.artworkKey != rawSnapshot.artworkKey
```

### 2. `previousLogical?.packageName` (Línea 2017):
```kotlin
        val appChanged = previousLogical?.packageName != rawSnapshot.packageName
```

### 3. `previousReliable?.projectedPositionMs` (Línea 2068):
```kotlin
        val lastProjectedPos = previousReliable?.projectedPositionMs() ?: 0L
```
