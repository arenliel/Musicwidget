package arenliel.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max

/**
 * Conjunto Icono-Refactor-1: registro permanente por paquete de los íconos "estáticos"
 * (monochrome y color) de las apps de música instaladas.
 *
 * A diferencia del ícono de notificación (inherentemente en vivo, distinto en cada sesión,
 * y por eso resuelto en MusicNotificationListener directamente), monochrome y color son
 * propiedades fijas de la APK instalada: se resuelven una sola vez por paquete y se
 * reutilizan para siempre, hasta que esa app se actualiza de verdad (se detecta comparando
 * PackageInfo.lastUpdateTime contra el valor guardado la última vez que se cacheó).
 *
 * Nota sobre los valores de tier: 2 = monochrome, 1 = color. Deben coincidir exactamente con
 * TIER_MONOCHROME/TIER_COLOR en MusicNotificationListener.kt — no se comparten como
 * constantes entre archivos a propósito, para no acoplar este registro (una utilidad
 * genérica de íconos) a la clase de servicio.
 */
object IconRegistry {

    private const val TIER_MONOCHROME = 2
    private const val TIER_COLOR = 1

    private const val DIR_NAME = "icon_registry"
    private const val MANIFEST_FILE = "manifest.json"

    private val mutex = Mutex()

    @Volatile private var manifestCache: JSONObject? = null

    private fun registryDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun manifestFile(context: Context): File =
        File(registryDir(context), MANIFEST_FILE)

    private fun loadManifest(context: Context): JSONObject {
        manifestCache?.let { return it }
        val file = manifestFile(context)
        val loaded = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        manifestCache = loaded
        return loaded
    }

    private fun saveManifest(context: Context, manifest: JSONObject) {
        manifestCache = manifest
        runCatching {
            val finalFile = manifestFile(context)
            val tempFile = File(registryDir(context), "$MANIFEST_FILE.tmp")
            tempFile.writeText(manifest.toString())
            try {
                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun monochromeFile(context: Context, packageName: String) =
        File(registryDir(context), "${packageName}_mono.webp")

    private fun colorFile(context: Context, packageName: String) =
        File(registryDir(context), "${packageName}_color.webp")

    private fun saveBitmapAtomically(file: File, bitmap: Bitmap) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(format, 90, out)
            out.flush()
        }
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Renderiza la capa monochrome respetando la resolución nativa para evitar pixelado.
     * (Trasladada desde MusicNotificationListener.getNativeAwareMonochromeBitmap, sin
     * cambios de lógica — solo recibe el Context como parámetro en vez de leerlo de un
     * campo de instancia de la clase de servicio.)
     */
    private fun getNativeAwareMonochromeBitmap(context: Context, drawable: Drawable): Bitmap {
        val density = context.resources.displayMetrics.density
        val standardSize = (108 * density).toInt()

        val intrinsicW = drawable.intrinsicWidth
        val intrinsicH = drawable.intrinsicHeight

        val renderSize = if (intrinsicW > 0 && intrinsicH > 0 && intrinsicW < standardSize) {
            max(intrinsicW, intrinsicH)
        } else {
            standardSize
        }

        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, renderSize, renderSize)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Devuelve el mejor ícono ESTÁTICO disponible para [packageName] (monochrome si existe,
     * si no color), sirviéndolo desde la caché en disco si sigue vigente, o calculándolo y
     * persistiéndolo si es la primera vez o si la app se actualizó de verdad desde la última
     * vez que se cacheó.
     */
    suspend fun resolveStaticIcon(context: Context, packageName: String, targetSizePx: Int): Pair<Bitmap, Int>? =
        mutex.withLock {
            val pm = context.packageManager
            val currentUpdateTime = runCatching {
                pm.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(-1L)

            val manifest = loadManifest(context)
            val entry = manifest.optJSONObject(packageName)
            val cachedUpdateTime = entry?.optLong("lastUpdateTime", -1L) ?: -1L
            val isFresh = entry != null && currentUpdateTime != -1L && currentUpdateTime == cachedUpdateTime

            if (isFresh) {
                val hasMono = entry!!.optBoolean("hasMonochrome", false)
                if (hasMono) {
                    val file = monochromeFile(context, packageName)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.let { return@withLock it to TIER_MONOCHROME }
                    }
                }
                val cFile = colorFile(context, packageName)
                if (cFile.exists()) {
                    BitmapFactory.decodeFile(cFile.absolutePath)?.let { return@withLock it to TIER_COLOR }
                }
                // El manifiesto dice que está vigente pero los archivos no están en disco:
                // seguimos abajo y recalculamos como si fuera la primera vez.
            }

            var monoBitmap: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    val appIcon = pm.getApplicationIcon(packageName)
                    if (appIcon is AdaptiveIconDrawable) {
                        appIcon.monochrome?.let { monochrome ->
                            val raw = getNativeAwareMonochromeBitmap(context, monochrome)
                            monoBitmap = ImageUtils.normalizeIcon(raw, isColorFallback = false, targetSizePx = targetSizePx)
                        }
                    }
                }
            }

            val colorBitmap: Bitmap? = runCatching {
                val raw = pm.getApplicationIcon(packageName).toBitmap()
                ImageUtils.normalizeIcon(raw, isColorFallback = true, targetSizePx = targetSizePx)
            }.getOrNull()

            val resolvedMono = monoBitmap
            if (resolvedMono != null) {
                saveBitmapAtomically(monochromeFile(context, packageName), resolvedMono)
            }
            if (colorBitmap != null) {
                saveBitmapAtomically(colorFile(context, packageName), colorBitmap)
            }

            val newEntry = JSONObject().apply {
                put("lastUpdateTime", currentUpdateTime)
                put("hasMonochrome", resolvedMono != null)
            }
            manifest.put(packageName, newEntry)
            saveManifest(context, manifest)

            when {
                resolvedMono != null -> resolvedMono to TIER_MONOCHROME
                colorBitmap != null -> colorBitmap to TIER_COLOR
                else -> null
            }
        }
}
