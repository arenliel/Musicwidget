# Auditoría — Ronda 21: ¿`trackChangedUI` Depende de `previousApplied`?

**Confirmación de Git Log:**
```
998c2ef (HEAD -> master) Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
```

---

## OO1. Declaración completa y actual de `trackChangedUI`

Ubicada en `MusicNotificationListener.kt` (Líneas 2358 - 2364):

```kotlin
2358:         val previousApplied = 
2359:             lastLogicalSnapshot
2360: 
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
```

---

## OO2. Confirmación directa

**SÍ.** La declaración de `trackChangedUI` utiliza `previousApplied` en su fórmula.

**Línea exacta (2362):**
```kotlin
            previousApplied?.trackKey != snapshot.trackKey
```
