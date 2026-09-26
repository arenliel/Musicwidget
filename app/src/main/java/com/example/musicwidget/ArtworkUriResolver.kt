package arenliel.musicwidget

import android.net.Uri

/**
 * Conjunto Portada-Fuente-Unica-1: unica fuente de verdad para traducir URIs de portada
 * conocidas que no son directamente descargables (por ejemplo la URI interna del content
 * provider de Spotify) a su equivalente HTTP publico. Antes esta logica vivia duplicada,
 * con una pequena divergencia real (ausencia de Uri.decode en una de las dos copias), en
 * MusicNotificationListener.kt y en ArtworkDetailActivity.kt.
 *
 * Si [uriString] no coincide con ningun caso conocido, se devuelve tal cual, sin cambios.
 */
object ArtworkUriResolver {

    private const val SPOTIFY_MEDIA_API_PREFIX = "content://com.spotify.mobile.android.mediaapi"
    private const val SPOTIFY_CDN_PREFIX = "https://i.scdn.co/image/"

    fun resolveKnownUri(uriString: String): String {
        if (uriString.startsWith(SPOTIFY_MEDIA_API_PREFIX)) {
            val hash = Uri.decode(uriString).substringAfterLast(":").substringBefore("?")
            if (hash.isNotBlank()) return "$SPOTIFY_CDN_PREFIX$hash"
        }
        return uriString
    }
}
