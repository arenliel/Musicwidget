package arenliel.musicwidget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ArtworkDetailActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ArtworkDetail"
        private const val MIN_HD_RESOLUTION = 800
        private const val ALBUM_ART_FILE = "album_art.webp"
        private const val ALBUM_ART_KEY_FILE = "album_art.key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val artworkUri = intent.getStringExtra("artwork_uri") ?: ""
        val artworkKey = intent.getStringExtra("artwork_key") ?: ""
        
        setContent {
            var bitmapState by remember { mutableStateOf<Bitmap?>(null) }
            val albumArtFile = File(filesDir, ALBUM_ART_FILE)
            val albumArtKeyFile = File(filesDir, ALBUM_ART_KEY_FILE)

            // Cargar imagen inicial desde disco
            LaunchedEffect(Unit) {
                val localBitmap = withContext(Dispatchers.IO) {
                    val savedKey = if (albumArtKeyFile.exists()) albumArtKeyFile.readText().trim() else ""
                    
                    // Solo cargamos del disco si la clave coincide perfectamente
                    if (albumArtFile.exists() && savedKey == artworkKey && artworkKey.isNotBlank()) {
                        BitmapFactory.decodeFile(albumArtFile.absolutePath)
                    } else {
                        null
                    }
                }
                bitmapState = localBitmap

                // Si la imagen local no coincide o es de baja resolución, descargar HD
                if (artworkUri.isNotBlank()) {
                    val needsUpgrade = localBitmap == null || 
                                     localBitmap.width < MIN_HD_RESOLUTION

                    if (needsUpgrade) {
                        Log.d(TAG, "Imagen local ausente, desincronizada o de baja resolución. Iniciando descarga HD...")
                        lifecycleScope.launch(Dispatchers.IO) {
                            val hdBitmap = downloadHD(artworkUri)
                            if (hdBitmap != null) {
                                withContext(Dispatchers.Main) {
                                    bitmapState = hdBitmap
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { finish() }
                    .padding(0.dp) // Sin márgenes
            ) {
                // Ya no usamos Surface para permitir la animación nativa de expansión
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    bitmapState?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Album Art Detail",
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(28.dp))
                                .clickable(enabled = false) {}
                        )
                    }
                }
            }
        }
    }

    private suspend fun downloadHD(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Manejar URIs de Spotify (opcional, si el URI viene sin procesar)
            val finalUrl = if (urlString.startsWith("content://com.spotify.mobile.android.mediaapi")) {
                val hash = urlString.substringAfterLast(":").substringBefore("?")
                "https://i.scdn.co/image/$hash"
            } else {
                urlString
            }

            if (!finalUrl.startsWith("http")) return@withContext null

            connection = URL(finalUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            if (connection.responseCode in 200..299) {
                BitmapFactory.decodeStream(connection.inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando imagen HD", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
