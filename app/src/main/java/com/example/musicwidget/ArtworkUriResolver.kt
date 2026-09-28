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

    // Conjunto Saneamiento-ArtworkUri-1: esquemas reconocidos como "esto es una URI real,
    // descargable o legible" (ver auditoria-conflacion-artworkuri-identidad-ronda1.md). Los
    // campos "artworkUri" de este proyecto NO siempre contienen una URI real — cuando una app
    // no entrega ninguna portada por ningun medio (metadata, bitmap embebido, notificacion),
    // el codigo reutiliza el mismo campo para guardar una clave interna de identidad
    // ("paquete|titulo|artista|album"), pensada solo para comparar, nunca para descargarse.
    private val FETCHABLE_URI_SCHEMES = listOf(
        "http://",
        "https://",
        "content://",
        "file://",
        "android.resource://"
    )

    fun resolveKnownUri(uriString: String): String {
        if (uriString.startsWith(SPOTIFY_MEDIA_API_PREFIX)) {
            val hash = Uri.decode(uriString).substringAfterLast(":").substringBefore("?")
            if (hash.isNotBlank()) return "$SPOTIFY_CDN_PREFIX$hash"
        }
        return uriString
    }

    /**
     * Verdadero solo si [uriString] tiene la forma de una URI real y utilizable — nunca una
     * clave de identidad interna, una ruta local sin esquema, una cadena vacia, u otro valor
     * que un campo "artworkUri" pueda llegar a contener por razones ajenas a tener o no una
     * portada real. Punto unico de verdad para esta pregunta: usar esto en vez de repetir
     * "no esta vacio" en cada sitio que decide si vale la pena intentar leer/descargar algo.
     */
    fun isFetchableUri(uriString: String): Boolean {
        return FETCHABLE_URI_SCHEMES.any { uriString.startsWith(it) }
    }
}
