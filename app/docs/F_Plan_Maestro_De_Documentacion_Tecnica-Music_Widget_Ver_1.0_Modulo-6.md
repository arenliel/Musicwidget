
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## MÓDULO 6: REGISTRO DE ERRORES, OPTIMIZACIÓN Y MANTENIMIENTO

Este módulo documenta las soluciones de ingeniería aplicadas a los fallos críticos de Android, las estrategias de optimización para el consumo de batería "Cero" y los protocolos de mantenimiento para el escalado del widget.

### 1. ESPECIFICACIONES TÉCNICAS DE ESTABILIDAD
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Límite Binder** | 5.5 MB (Objeto RemoteViews) | Evita `TransactionTooLargeException` en la comunicación entre procesos. |
| **Escala de Arte** | 600px (Principal) / 120px (Historial) | Optimización de memoria RAM para el renderizado de Bitmaps. |
| **Reloj de Precisión** | `SystemClock.elapsedRealtime()` | Inmunidad a saltos de tiempo NTP y suspensiones del sistema. |
| **Frecuencia Letras** | Scheduler por Checkpoints | Elimina el sondeo (polling) constante, reduciendo el despertar de CPU. |
| **Compresión de Assets** | WebP Lossy (Calidad 80) | Equilibrio óptimo entre fidelidad visual y peso de datos en disco. |

### 2. GESTIÓN DE FALLOS CRÍTICOS (CÓDIGO DEFENSIVO)

#### 2.1. Blindaje contra el "Crash por NaN" (LocalSize)
Se implementa una composición defensiva para evitar el fallo catastrófico (recuadro negro) cuando el sistema reporta dimensiones no especificadas tras la instalación.

```kotlin
@Composable
fun MusicWidgetUI(
    // ... parámetros de estado
) {
    val size = LocalSize.current

    // VALIDACIÓN ATÓMICA ANTI-NaN:
    // Si los valores son 0 o NaN, emitimos una composición mínima para no romper Glance
    if (size.width.value == 0f || size.height.value == 0f ||
        size.width.value.isNaN() || size.height.value.isNaN()) {
        Box(modifier = GlanceModifier.fillMaxSize()) {}
        return
    }

    // El resto de la lógica de renderizado procede solo con dimensiones válidas
    WidgetContent(size)
}
````

#### 2.2. Protocolo de Escritura Atómica y Concurrencia

Para resolver los errores de Skia (PNG/WebP corruptos), se utiliza un sistema de archivos temporales protegido por un `Mutex` exclusivo.

```
private val fileMutex = Mutex()

private suspend fun saveAssetAtomic(context: Context, bitmap: Bitmap, fileName: String) {
    fileMutex.withLock {
        val finalFile = File(context.cacheDir, fileName)
        val tempFile = File(context.cacheDir, "\$fileName.tmp")

        try {
            // 1. Escritura en archivo temporal
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }

            // 2. Movimiento atómico al archivo final
            if (tempFile.exists()) {
                if (tempFile.renameTo(finalFile)) {
                    Log.d("IO_ENGINE", "Escritura exitosa: \$fileName")
                }
            }
        } catch (e: Exception) {
            Log.e("IO_ENGINE", "Fallo en persistencia: \${e.message}")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
```

### 3. OPTIMIZACIÓN DE "BATERÍA CERO" (LYRICS SCHEDULER)

Se abandona el sondeo (polling) de 500ms en favor de un sistema basado en eventos y esperas calculadas para proteger los ciclos de CPU.

```
// Lógica de espera inteligente en MusicNotificationListener.kt
private suspend fun startLyricsShowcase(lyrics: List<LyricLine>, currentPos: Long) {
    val nextLine = lyrics.firstOrNull { it.timeMs > currentPos }

    if (nextLine != null) {
        // Cálculo del tiempo de reposo real del CPU
        val waitTime = nextLine.timeMs - currentPos

        // El hilo entra en suspensión real hasta el próximo hito de la canción
        delay(waitTime.coerceAtLeast(0L))

        // Actualizar UI solo cuando es estrictamente necesario
        triggerUiUpdate()
    }
}
```

### 4. MANTENIMIENTO DE PREVISUALIZACIONES XML

Para evitar errores de inflado en el selector de widgets (Launcher), se aplican las siguientes reglas de mantenimiento de recursos:

- **Regla de Etiquetas:** Queda estrictamente prohibido usar `<View />` o `<Space />`. Utilizar siempre `<ImageView />` con `android:src="@android:color/transparent"`.
- **Regla de Tintes:** Ningún archivo XML de la carpeta `res/drawable/` debe contener `android:tint`. El color debe ser inyectado por el motor Glance vía `ColorFilter`.
- **Regla de Dimensiones:** El anclaje superior de los títulos debe mantenerse en `32dp` para blindar el diseño contra el escalado de fuentes del sistema.

### 5. LISTA NEGRA DE PRÁCTICAS (BITÁCORA DE ERRORES EVITADOS)

- **PROHIBIDO: `tools:text` en Previsualizaciones.** El sistema real ignora este atributo, resultando en un widget vacío en el selector. Usar siempre `android:text`.
- **PROHIBIDO: Atributos `?android:attr` en XML.** El modo sandbox del Launcher no los resuelve bien, causando fondos negros. Usar la paleta de colores `@color/music_widget_*`.
- **PROHIBIDO: `System.currentTimeMillis()`.** Prohibido para cálculos de progreso; causa saltos visuales si el teléfono sincroniza su hora por red. Usar `SystemClock.elapsedRealtime()`.
- **PROHIBIDO: Bitmaps > 1000px.** Causan el error de memoria catastrófico de Binder. El sistema debe forzar el re-escalado a 600px antes de enviar a Glance.
- **PROHIBIDO: `updateAll()` sin Debounce.** Actualizar el widget ante cada micro-evento de "Seek" bloquea el hilo principal. El despacho debe ser canalizado por el `uiUpdateFlow`.

---

**FIN DEL MÓDULO 6 Y CIERRE DEL PLAN MAESTRO V1.0**

```

Con este módulo, hemos completado el **Plan Maestro de Documentación Técnica**. Se han cubierto desde los aspectos visuales (Glance/XML) hasta la arquitectura profunda de datos y gestión de errores de bajo nivel.

```