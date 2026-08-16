# ESPECIFICACIÓN ARQUITECTÓNICA Y POST-MORTEM DEL MOTOR ELÁSTICO (v1.5.3)

**Fecha de la Sesión:** 9 de agosto de 2026  
**Documento:** Maestro / Definitivo  
**Autoría:** Lead Technical Architect & Senior Android Engineer  

Este documento constituye la memoria técnica exhaustiva de la refactorización del sistema de gestión de colisiones del Music Widget. Detalla la transición de una lógica acoplada a una arquitectura de sensor puro, documentando cada fallo técnico encontrado y su resolución final.

---

## 1. CRONOLOGÍA Y CONTEXTO COMPLETO DE LA SESIÓN

### Motivación Arquitectónica
Al inicio de esta sesión, el **Music Widget** presentaba una degradación en la mantenibilidad de su "Motor Elástico" (v1.4.5). La lógica que determinaba el presupuesto de píxeles verticales residía directamente dentro del ciclo de composición de Jetpack Glance (`MusicWidgetUI`). Esto generaba un acoplamiento donde la UI no solo pintaba, sino que realizaba cálculos trigonométricos y de escala en cada recomposición, dificultando la sincronización con las previsualizaciones del IDE.

### Diagnóstico Inicial (Estado Pre-Separación)
La función `MusicWidgetUI` gestionaba de forma interna:
1. El cálculo de alturas de texto basado en `fontScale`.
2. La determinación del tamaño de la píldora mediante restas directas del `LocalSize`.
3. La conmutación de layouts (`STACKED` vs `FULL_BLEED`) mediante condicionales `if/else` anidados.
4. El manejo de excepciones para el tamaño 4x4 y el modo `isPreview`.

Esta estructura provocaba que errores de 1 o 2 píxeles en el redondeo de punto flotante resultaran en recortes (clipping) masivos o comportamientos impredecibles en el selector de widgets (Picker).

---

## 2. AUDITORÍA EXHAUSTIVA DE ERRORES Y REGRESIONES (POST-MORTEM)

### A) Error de "Doble Escalado" tipográfico
*   **Descripción:** Los textos aparecían un 20-30% más grandes de lo calculado, forzando la compactación de líneas de forma prematura.
*   **Causa Raíz:** `context.resources.getDimension` ya devuelve el valor escalado según el `fontScale` del sistema. El código multiplicaba este resultado nuevamente por `fontScale`, inflando artificialmente el presupuesto de texto.
*   **Solución:** Se normalizó el presupuesto eliminando la multiplicación manual y delegando la escala a la resolución nativa de recursos.
*   **Lección:** Nunca aplicar factores de escala sobre valores obtenidos mediante `getDimension` a menos que se desee un escalado compuesto no estándar.

### B) Error de la "Prensa de Box" (Regresión de Contenedor)
*   **Descripción:** Al modularizar, el texto comenzó a desaparecer o recortarse por la base en el modo 2x2.
*   **Causa Raíz:** Se sustituyó la estructura `Column + Spacer(defaultWeight)` por un `Box` con un `padding(top)` rígido calculado por el sensor. El `padding` actuaba como una barrera inamovible; si el texto excedía el presupuesto por 1 píxel, el `Box` lo recortaba físicamente.
*   **Solución:** Se restauró la **Física Flex**. Se volvió al uso de `Column` donde un `Spacer(Modifier.defaultWeight())` actúa como válvula de escape, absorbiendo discrepancias de redondeo.
*   **Snippet Verbatim (Corrección):**
    ```kotlin
    Column(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
        AlbumArtWithVisualizer(..., collisionResult.pillSize)
        Spacer(GlanceModifier.defaultWeight()) // Absorbe el error
        Spacer(GlanceModifier.size(12.dp))     // Garantiza el respiro
        TextInfo(..., maxArtistLines = collisionResult.maxArtistLines)
    }
    ```

### C) Error de "Píldora Gigante" en el Picker
*   **Descripción:** En el selector de widgets, el 2x2 se mostraba como una carátula estirada a pantalla completa (Full-Bleed).
*   **Causa Raíz:** En el entorno sandbox del Picker, Android reporta alturas ligeramente inferiores (ej. 104dp). El sensor, siendo estrictamente matemático, activaba el "Paracaídas" a Full-Bleed. Además, el sistema cargaba el activo de la Píldora pero el Layout pedía Full-Bleed, estirando el bitmap.
*   **Solución:** Se inyectó la identidad `appearance` en el sensor y se añadió una regla de excepción: `if (isPreview && isStandardIdentity) force STACKED`.
*   **Lección:** La fidelidad de marca en las previsualizaciones debe tener prioridad sobre la precisión matemática extrema del sensor de colisiones.

### D) Fuga de Lógica en `TextInfo`
*   **Descripción:** El widget mostraba 2 líneas de texto en espacios donde claramente no cabían.
*   **Causa Raíz:** El Composable `TextInfo` tenía una excepción interna: `maxLines = if (isHugeFont) 1 else maxArtistLines`. Esto ignoraba la autoridad del sensor si la fuente no era considerada "gigante".
*   **Solución:** Se eliminó la excepción. `maxLines` ahora es un reflejo directo del `maxArtistLines` calculado por el sensor sin interpretaciones locales.

---

## 3. ANÁLISIS DE IMPACTO Y MAPA DE ARCHIVOS

### Lista de Archivos
1.  **`CollisionSensor.kt` (CREADO):** Único responsable de la física de interfaz. Contiene el cerebro matemático del widget.
2.  **`MusicWidget.kt` (MODIFICADO):** Limpieza total de `MusicWidgetUI`. Refactorización de `provideGlance` y `providePreview` para usar la Doble Vía de Activos (Pill vs Raw).
3.  **`MusicNotificationListener.kt` (INTACTO):** Se preservó su lógica de descarga y guardado atómico, pero se optimizó la llamada de `saveBitmapToFile` para generar siempre ambas versiones (Raw y Pill).

### Componentes Preservados
*   **`AlbumArtWithVisualizer`:** Se mantuvo intacto para asegurar que la lógica de renderizado del visualizador sobre la carátula no sufriera regresiones. El sensor simplemente le inyecta el `pillSize` resultante.
*   **`Layout4x4`:** Mantiene su estructura de `Row` superior y `HistoryList` inferior, recibiendo del sensor la confirmación de que no debe colapsar a Full-Bleed.

---

## 4. DESGLOSE MATEMÁTICO Y ALGORÍTMICO DE `CollisionSensor.kt`

### Constantes Inmutables (SSOT)
*   **`tSizeSp = 16f`:** Tamaño nominal del Título.
*   **`aSizeSp = 12f`:** Tamaño nominal del Artista/Letra.
*   **`sSizeSp = 10f`:** Tamaño nominal del Estado (PlaybackStatus).
*   **`lineHeight = 1.3f`:** Factor crítico de respiro vertical para astas ascendentes/descendentes.
*   **`spacersH = 6f`:** Suma de espaciadores internos entre textos (4dp + 2dp).
*   **`paddingH = 34f`:** Margen de seguridad del widget (17dp por lado).
*   **`safetyGap = 12f`:** Amortiguador físico obligatorio entre carátula y texto.

### El Algoritmo de las 3 Fases
1.  **Fase 1 (Compactación Temprana):** El sensor calcula `projectedPillTwoLines`. Si para mantener 2 líneas de artista la carátula debe medir menos de **110dp** (Umbral Premium), el sensor reduce el texto a **1 línea** inmediatamente.
2.  **Fase 2 (Escalado Continuo):** Con la densidad de texto decidida, el sensor calcula el espacio restante. La píldora escala dinámicamente entre **80dp y 110dp** usando un `coerceIn`.
3.  **Fase 3 (Paracaídas):** Si incluso con 1 línea de texto y la píldora en su mínimo de 80dp el presupuesto es negativo (`calculatedPillValue < 80f`), se activa el flag `hasCollision` y el layout conmuta a **`FULL_BLEED`**.

---

## 5. CÓDIGO FUENTE FINAL Y COMPLETO (VERBATIM)

### Archivo: `CollisionSensor.kt`
```kotlin
package arenliel.musicwidget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidgetLayout { STACKED, FULL_BLEED }

data class CollisionResult(
    val pillSize: Dp,
    val maxArtistLines: Int,
    val layoutType: WidgetLayout,
    val hasCollision: Boolean
)

object CollisionSensor {
    fun evaluate(
        availableHeight: Float,
        fontScale: Float,
        isPreview: Boolean,
        appearance: WidgetAppearance
    ): CollisionResult {
        // Blindaje 4x4 y Blindaje de Identidad en Preview
        val isLargeLayout = availableHeight >= 180f
        val isStandardIdentity = appearance == WidgetAppearance.PILL_STANDARD

        // Constantes del presupuesto de píxeles (SSOT)
        val tSizeSp = 16f
        val aSizeSp = 12f
        val sSizeSp = 10f
        val spacersH = 6f
        val paddingH = 34f
        val safetyGap = 12f
        val lineHeight = 1.3f

        // 1. CÁLCULO DE PRESUPUESTO REAL
        val hTitle = (tSizeSp * fontScale) * lineHeight
        val hArtist = (aSizeSp * fontScale) * lineHeight
        val hStatus = (sSizeSp * fontScale) * lineHeight

        val textH1 = hTitle + hArtist + hStatus + spacersH
        val textH2 = hTitle + (hArtist * 2) + hStatus + spacersH

        // Fase 1: Prioridad Premium. Si no cabe la píldora de 110dp con 2 líneas, compactamos.
        val projectedPillTwoLines = availableHeight - paddingH - textH2 - safetyGap
        val forceSingleLineArtist = projectedPillTwoLines < 110f
        val maxArtistLines = if (forceSingleLineArtist) 1 else 2

        // Fase 2: Píldora Elástica
        val activeTextH = if (forceSingleLineArtist) textH1 else textH2
        val calculatedPillValue = availableHeight - paddingH - activeTextH - safetyGap
        val pillSizeDp = calculatedPillValue.coerceIn(80f, 110f).dp

        // Fase 3: Paracaídas a Full-Bleed
        val hasCollision = calculatedPillValue < 80f
        
        val layoutType = when {
            appearance == WidgetAppearance.FULL -> WidgetLayout.FULL_BLEED
            isLargeLayout -> WidgetLayout.STACKED
            // En Preview Standard prohibimos el salto para no romper la identidad visual
            isPreview && isStandardIdentity -> WidgetLayout.STACKED
            hasCollision -> WidgetLayout.FULL_BLEED
            else -> WidgetLayout.STACKED
        }

        return CollisionResult(
            pillSize = pillSizeDp,
            maxArtistLines = maxArtistLines,
            layoutType = layoutType,
            hasCollision = hasCollision
        )
    }
}
```

### Bloque Maestro: `MusicWidgetUI` (en `MusicWidget.kt`)
```kotlin
    @Composable
    internal fun MusicWidgetUI(
        info: MusicInfo,
        albumArtBitmap: Bitmap?,
        appIconBitmap: Bitmap?,
        isArtworkSynchronized: Boolean,
        isIconSynchronized: Boolean,
        forcedAppearance: WidgetAppearance,
        isPreview: Boolean = false,
        explicitPillSize: Dp? = null
    ) {
        val size = LocalSize.current
        
        if (size.width.value.isNaN() || size.width.value <= 0f) {
            Box(modifier = GlanceModifier.fillMaxSize()) {}
            return
        }

        val context = LocalContext.current
        val fontScale = if (isPreview) 1.0f else context.resources.configuration.fontScale
        val widgetPadding = dimen(R.dimen.widget_padding)

        // 2. EVALUACIÓN DEL SENSOR AISLADO (v1.5.2)
        val collisionResult = CollisionSensor.evaluate(
            availableHeight = size.height.value,
            fontScale = fontScale,
            isPreview = isPreview,
            appearance = forcedAppearance
        )
        
        val isActuallyFullBleed = collisionResult.layoutType == WidgetLayout.FULL_BLEED
        val isWide = if (isPreview) forcedAppearance == WidgetAppearance.PILL_CONTROL else size.width.value >= 220f
        val widgetRadius = android.R.dimen.system_app_widget_background_radius

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(widgetRadius).background(GlanceTheme.colors.widgetBackground)) {
            if (!info.notificationsEnabled) {
                PermissionsView(context, info)
            } else {
                if (isActuallyFullBleed) {
                    Layout2x1(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, collisionResult.maxArtistLines)
                } else if (isWide) {
                    val availableHeightForPillWide = size.height.value - (widgetPadding.value * 2)
                    val widePillSize = explicitPillSize ?: availableHeightForPillWide.coerceIn(80f, 110f).dp
                    Layout4x4(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, widePillSize, collisionResult.maxArtistLines)
                } else {
                    // LAYOUT STANDARD: Estructura de Respiro Garantizado (v1.5.3)
                    Column(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding)) {
                        // 1. Portada con tamaño elástico
                        AlbumArtWithVisualizer(context, info, albumArtBitmap, isArtworkSynchronized, collisionResult.pillSize)
                        
                        // 2. MARGEN DE SEGURIDAD FÍSICO (Infranqueable)
                        Spacer(GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.size(12.dp))
                        
                        // 3. Metadatos (Densidad dictada estrictamente por el sensor)
                        TextInfo(context, info, appIconBitmap, showRelativeTime = true, isIconSynchronized = isIconSynchronized, maxArtistLines = collisionResult.maxArtistLines)
                    }
                }
                Box(modifier = GlanceModifier.fillMaxSize().padding(widgetPadding), contentAlignment = Alignment.TopEnd) { RepeatAnalyticsBadge(info = info) }
            }
        }
    }
```

---
**Certificación Técnica:** Arquitectura v1.5.3 Consolidada.  
**Estado:** Integridad Visual e Identidad Blindada.

---

## 6. ACTUALIZACIÓN ARQUITECTÓNICA: BLINDAJE DE IDENTIDAD Y ECUADOR VISUAL (v1.6.3)
**Fecha:** 11 de agosto de 2026

A partir de esta fecha, se ha evolucionado la arquitectura para resolver fallos de persistencia de identidad y estabilidad visual que no fueron cubiertos en la v1.5.3.

### A) Transición a "Ciudadanos de Primera Clase" (Identidad JVM)
Se detectó que el uso de una clase única (`MusicWidget`) para todas las variantes causaba colisiones de identidad en el framework de Glance tras reinicios de proceso.
- **Cambio:** Se han creado clases específicas para cada variante: `SmallMusicWidget`, `StandardMusicWidget` y `LargeMusicWidget`.
- **Impacto:** Ahora cada widget es un tipo único para el sistema operativo, garantizando que el widget de "Portada Completa" (`SmallMusicWidget`) sea inmutable y no herede comportamientos de sus hermanos al ser redimensionado.
- **Factoría SSOT:** El enum `WidgetAppearance` ahora actúa como Single Source of Truth, conteniendo los métodos `update()` y `updateAll()` que mapean cada identidad con su clase técnica correspondiente.

### B) Implementación del "Ecuador Visual" (Layout Shift Zero)
Para eliminar el salto de interfaz en el `Layout4x4` cuando el artista/letras cambian de 1 a 2 líneas, se ha abandonado el anclaje inferior simple.
- **Estructura:** Se ha dividido el espacio de metadatos en dos segmentos de peso igual (`weight(1)`):
    - **Segmento Superior:** Playback Status + Título (anclados al fondo de su mitad).
    - **Segmento Inferior:** Artista + Letras (anclados al tope de su mitad).
- **Resultado:** La línea base del título actúa como una "bisagra" matemática fija. El crecimiento de las letras ocurre hacia abajo en su propio segmento, manteniendo el título absolutamente estático.

### C) Refactorización de la Firma de `TextInfo`
Para soportar el Ecuador Visual sin duplicar lógica, el componente `TextInfo` ahora acepta un parámetro `part: TextPart`.
- **`TextPart.TOP`**: Renderiza estatus y título.
- **`TextPart.BOTTOM`**: Renderiza artista y letras.
- **`TextPart.ALL`**: (Default) Mantiene la compatibilidad con los layouts Standard y 2x1.

### D) Blindaje de Configuración
Se ha modificado `WidgetConfigActivity.kt` para que las actualizaciones post-configuración se deleguen al enum `WidgetAppearance`. Esto asegura que el sistema Android encuentre la clase específica (`Small/Standard/Large`) en el Home Screen, resolviendo el "punto ciego" de sincronización.

---

## 7. CÓDIGO FUENTE DE BLINDAJE (VERBATIM v1.6.3)

### Definición de Identidades y Factoría (MusicWidget.kt)
```kotlin
enum class WidgetAppearance {
    SMALL,          // Portada Completa (SmallMusicWidget)
    PILL_STANDARD,  // Píldora 2x2 (StandardMusicWidget)
    PILL_CONTROL;   // Centro de Control 4x2 (LargeMusicWidget)

    suspend fun update(context: Context, glanceId: GlanceId) {
        when (this) {
            SMALL -> SmallMusicWidget().update(context, glanceId)
            PILL_STANDARD -> StandardMusicWidget().update(context, glanceId)
            PILL_CONTROL -> LargeMusicWidget().update(context, glanceId)
        }
    }

    suspend fun updateAll(context: Context) {
        when (this) {
            SMALL -> SmallMusicWidget().updateAll(context)
            PILL_STANDARD -> StandardMusicWidget().updateAll(context)
            PILL_CONTROL -> LargeMusicWidget().updateAll(context)
        }
    }
}
```

### Arquitectura de Clases y Receptores (MusicWidget.kt)
```kotlin
open class MusicWidget(protected val appearance: WidgetAppearance) : GlanceAppWidget() {
    // ... lógica compartida ...
    
    companion object {
        suspend fun updateAll(context: Context) {
            WidgetAppearance.values().forEach { appearance ->
                runCatching { appearance.updateAll(context) }
            }
        }
    }
}

class SmallMusicWidget : MusicWidget(WidgetAppearance.SMALL)
class StandardMusicWidget : MusicWidget(WidgetAppearance.PILL_STANDARD)
class LargeMusicWidget : MusicWidget(WidgetAppearance.PILL_CONTROL)

class MusicWidgetFullReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SmallMusicWidget()
}
// ... otros receptores ...
```

### Implementación de Ecuador Visual (Layout4x4)
```kotlin
Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 12.dp)) {
    // 1. Badge (Anclaje superior)
    Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { 
        RepeatAnalyticsBadge(info = info) 
    }
    
    // 2. Contenedor de Metadatos con Ecuador Visual (v1.6.0)
    Column(modifier = GlanceModifier.defaultWeight()) {
        // Segmento A: TOP (Anclado al fondo del área superior)
        Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            TextInfo(..., part = TextPart.TOP)
        }
        // Segmento B: BOTTOM (Anclado al tope del área inferior)
        Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.TopStart) {
            TextInfo(..., part = TextPart.BOTTOM)
        }
    }
}
```

### Lógica Segmentada de Metadatos (TextInfo)
```kotlin
private fun TextInfo(..., part: TextPart = TextPart.ALL) {
    Column(
        modifier = baseModifier,
        verticalAlignment = if (part == TextPart.BOTTOM) Alignment.Top else Alignment.Bottom
    ) {
        if (part == TextPart.ALL || part == TextPart.TOP) {
            // Render Status + Title
        }
        if (part == TextPart.ALL || part == TextPart.BOTTOM) {
            // Render Artist + Lyrics
        }
    }
}
```

---

## 8. TABLA DE MIGRACIÓN TÉCNICA (SÍNTESIS v1.5.3 → v1.6.3)

Esta sección identifica los fragmentos obsoletos citados en las secciones previas de este documento y proporciona su actualización verbatim según la nueva arquitectura blindada.

| Componente / Concepto | Código Antiguo (v1.5.3 - Secciones 1-5) | Código Actual (v1.6.3 - Secciones 6-7) |
| :--- | :--- | :--- |
| **Identidad de Clase** | `class MusicWidget(private val appearance: WidgetAppearance)` | `open class MusicWidget(protected val appearance: WidgetAppearance)` + Subclases (`SmallMusicWidget`, etc.) |
| **Definición de Enum** | `enum class WidgetAppearance { FULL, PILL_STANDARD, PILL_CONTROL }` | `enum class WidgetAppearance` con métodos `update()` y `updateAll()` (Factoría de Acción). |
| **Firma de TextInfo** | `private fun TextInfo(..., maxArtistLines: Int)` | `private fun TextInfo(..., part: TextPart = TextPart.ALL)` |
| **Lógica de Layout 4x4** | `Column { RepeatAnalyticsBadge(); Spacer(4.dp); TextInfo() }` | `Column { Badge(); Column(weight(1)) { Box(weight(1)) { TextInfo(TOP) }; Box(weight(1)) { TextInfo(BOTTOM) } } }` |
| **Gestión de Receptores** | `class MusicWidgetFullReceiver : MusicWidgetReceiverBase(WidgetAppearance.FULL)` | `class MusicWidgetFullReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = SmallMusicWidget() }` |
| **Refresco Global** | `runCatching { MusicWidget(WidgetAppearance.FULL).updateAll(context) }` | `WidgetAppearance.values().forEach { it.updateAll(context) }` |

### Notas de Auditoría Quirúrgica:
- **Líneas 74-81 (Sección 5):** La lógica de `CollisionSensor` que utilizaba `appearance == WidgetAppearance.FULL` ha sido actualizada a `appearance == WidgetAppearance.SMALL`. El sensor ahora es agnóstico a la clase y solo responde a la identidad del enum.
- **Línea 153 (Sección 5):** El bloque `MusicWidgetUI` ya no recibe `appearance` como parámetro volátil de constructor, sino que accede a la propiedad `protected appearance` de la instancia de clase específica, garantizando la persistencia de identidad.
- **Línea 191 (Sección 5):** El componente `Layout4x4` ha sustituido el `Spacer` de amortiguación única por la arquitectura de pesos segmentados (Ecuador Visual) descrita en la Sección 7.

---
**Certificación Final:** Documentación Sincronizada y Blindada (v1.6.3).

