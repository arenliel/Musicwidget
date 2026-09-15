# Auditoría — Ronda 8 (Portadas): Firma Exacta del Mecanismo que Convierte Bitmap en Archivo con URI

**Confirmación de Git Log:**
```
e54d9d0 (HEAD -> master) Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
998c2ef Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
```

---

## AC1. Firma completa de la función que realiza el "Trabajo 2" (Bitmap → archivo con URI)

Ubicada en `ArtworkStorageManager.kt` (Línea 25):

```kotlin
25:     fun saveHistoryArtwork(context: Context, bitmap: Bitmap, identifier: String): String {
```

**Parámetros:**
- `context: Context`: Para acceder al sistema de archivos del sistema.
- `bitmap: Bitmap`: Los píxeles de la portada resuelta.
- `identifier: String`: Un identificador de texto (típicamente el `sessionUUID`) que se usa para nombrar el archivo físico.

**Valor de retorno:**
- `String`: Devuelve la **ruta absoluta** del archivo guardado en disco.

---

## AC2. ¿Puede llamarse desde fuera del contexto de "nace una sesión nueva"?

**SÍ.** La función es una utilidad estática (`object ArtworkStorageManager`) que no depende de ningún estado interno de la clase `MusicNotificationListener` ni del ciclo de vida de creación de la sesión.

Solo requiere:
1.  Un objeto `Bitmap` válido.
2.  Una cadena `String` para usar como nombre de archivo.

**Confirmación técnica:** En una "corrección en caliente" (Stage 2 de `processSnapshot`), se tiene acceso tanto al `resolvedArtwork` (el Bitmap) como al `session?.sessionUUID` (el identificador de la sesión que sigue sonando). Por lo tanto, esta función puede ser invocada en cualquier momento para generar un archivo físico y obtener una ruta (`String`) que sea compatible con el campo `artworkUri` de `MusicInfo`.
