# Auditoría — Ronda 11: Accesibilidad de `reason` dentro de `[FSM_GUARD]`

**Confirmación de Git Log:**
```
0c0c26d (HEAD -> master) Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
0d2d925 Conjunto Cierres-1: Fuente de Verdad Confiable (lastAppliedSnapshot) en el FSM_GUARD
```

---

## CC1. ¿La función `[FSM_GUARD]` recibe `reason` como parámetro?

La función que produce el log `[FSM_GUARD]` es `processSnapshot`. Su firma completa es la siguiente:

```kotlin
1906:     private suspend fun processSnapshot(
1907:         controller: MediaController?,
1908:         metadata: MediaMetadata?,
1909:         rawSnapshot: MediaSnapshot,
1910:         reason: String
1911:     ) {
```

**Confirmación:** SÍ, la función recibe `reason` como el cuarto parámetro de tipo `String`.

---

## CC2. Si no lo recibe, ¿dónde se llama esta función, y se le puede pasar `reason` desde ahí?

Como se confirmó en CC1, la función ya recibe `reason`. El único punto de llamada en el proyecto es dentro de `refreshBestSession`:

```kotlin
1581:         processSnapshot(
1582:             controller =
1583:                 controller,
1584:             metadata =
1585:                 metadata,
1586:             rawSnapshot =
1587:                 snapshot,
1588:             reason =
1589:                 reason
1590:         )
```

En este punto, `reason` proviene de los parámetros de `refreshBestSession`, la cual es el orquestador principal de las actualizaciones del widget.

---

**Nota Técnica:** Aunque `processSnapshot` tiene acceso a `reason`, el log `[FSM_GUARD]` (línea 2099) actualmente no lo incluye en su salida.
