package arenliel.musicwidget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.io.File

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@Composable
fun RestrictedSettingsCard(onOpenInfo: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Elevación máxima (v5.0)
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Default.Info, 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.onSurface, 
                modifier = Modifier.size(24.dp).padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.setup_restricted_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.setup_restricted_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button( // Botón sólido y claro como en la referencia
                    onClick = onOpenInfo, 
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(stringResource(R.string.setup_restricted_button), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun BatteryOptimizationCard(isIgnoring: Boolean, onToggle: () -> Unit, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), 
        shape = shape
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.cached_24px), 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.onSurface, 
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.setup_battery_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = stringResource(R.string.setup_battery_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isIgnoring, 
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
        }
    }
}

@Composable
fun PermissionCard(isGranted: Boolean, onGrantClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape) {
    Card(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), 
        shape = shape
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.stock_media_24px), 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.onSurface, 
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = stringResource(R.string.setup_notifications_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.setup_notifications_desc), 
                style = MaterialTheme.typography.bodySmall, 
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp)
            )
            
            if (!isGranted) {
                Spacer(modifier = Modifier.height(16.dp))
                Button( // Botón sólido y claro (v5.0)
                    onClick = onGrantClick, 
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.padding(start = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(stringResource(R.string.setup_notifications_button), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppListContent(apps: List<AppItem>, blacklist: Set<String>, onToggle: (String, Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
        Text(text = stringResource(R.string.setup_whitelist_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center)
        Text(text = stringResource(R.string.setup_whitelist_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps) { app ->
                val isSelected = !blacklist.contains(app.packageName)
                BlacklistItem(app = app, isBlacklisted = !isSelected, onToggle = { checked -> onToggle(app.packageName, !checked) })
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun BlacklistItem(app: AppItem, isBlacklisted: Boolean, onToggle: (Boolean) -> Unit) {
    val isSelected = !isBlacklisted
    ListItem(
        modifier = Modifier.clickable { onToggle(!isBlacklisted) },
        headlineContent = { Text(text = app.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = { Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape)) },
        trailingContent = { Checkbox(checked = isSelected, onCheckedChange = { onToggle(!isSelected) }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)) },
        colors = ListItemDefaults.colors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable
fun DiagnosticSheetContent(context: Context) {
    var errorText by remember { mutableStateOf("No se han detectado errores recientes.") }
    val scrollState = rememberScrollState()
    LaunchedEffect(Unit) {
        val file = File(context.filesDir, "widget_error.log")
        if (file.exists()) errorText = file.readText()
    }
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(horizontal = 24.dp)) {
        Text(text = "Diagnóstico Técnico", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp)) {
            Box(modifier = Modifier.padding(12.dp).verticalScroll(scrollState)) {
                Text(text = errorText, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (errorText.contains("Error") && errorText.length > 50) TextAlign.Start else TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val hasError = errorText.contains("Error") || errorText.length > 50
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Widget Error Log", errorText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f), enabled = hasError, shape = RoundedCornerShape(16.dp)) { Text("Copiar Log") }
            OutlinedButton(onClick = {
                val file = File(context.filesDir, "widget_error.log")
                if (file.exists()) file.delete()
                errorText = "Log limpiado con éxito."
            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Limpiar") }
        }
    }
}
