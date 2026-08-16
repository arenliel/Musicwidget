package arenliel.musicwidget

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.preview.ExperimentalGlancePreviewApi

/**
 * AUDITOR DE MAPAS DE SOMBRAS (Initial Layouts)
 */

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 60)
@Composable
fun ShadowMapAuditor_Small() {
    GlanceTheme {
        AndroidRemoteViews(remoteViews = android.widget.RemoteViews("arenliel.musicwidget", R.layout.glance_loading_full))
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 180, heightDp = 180)
@Composable
fun ShadowMapAuditor_Standard() {
    GlanceTheme {
        AndroidRemoteViews(remoteViews = android.widget.RemoteViews("arenliel.musicwidget", R.layout.glance_loading_standard))
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 340, heightDp = 340)
@Composable
fun ShadowMapAuditor_Large() {
    GlanceTheme {
        AndroidRemoteViews(remoteViews = android.widget.RemoteViews("arenliel.musicwidget", R.layout.glance_loading_large))
    }
}
