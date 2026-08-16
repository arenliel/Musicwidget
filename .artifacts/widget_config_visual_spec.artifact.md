# Desglose Visual y Tematización: WidgetConfigActivity

Este documento detalla el lenguaje de diseño, los recursos gráficos y el sistema de tematización aplicado a la pantalla de configuración del Music Widget.

## 1. Sistema de Tematización (Material 3)

La actividad utiliza **Material Design 3 (M3)** como base, integrando capacidades modernas de personalización.

### A. Colores Dinámicos (Material You)
- **Implementación:** A través de `MusicWidgetTheme`.
- **Comportamiento:** Si el dispositivo corre Android 12 (API 31) o superior, los colores de la interfaz se sincronizan con el fondo de pantalla del usuario mediante `dynamicLightColorScheme` o `dynamicDarkColorScheme`.
- **Paleta de Respaldo:** En versiones anteriores, utiliza una paleta basada en tonos púrpuras y grises (`Purple40/80`).

### B. Tipografía
Se utiliza la escala tipográfica estándar de M3 para jerarquizar la información:
- **`displaySmall`**: Título principal ("Ajustes de música").
- **`labelLarge`**: Encabezados de sección ("Seguridad", "Comportamiento") con el color `primary`.
- **`titleMedium` / `bodyMedium`**: Títulos y descripciones en tarjetas y elementos de lista.
- **`labelSmall`**: Textos técnicos y secundarios con baja opacidad (`alpha = 0.6f`).

## 2. Componentes Visuales y Estructura

La interfaz se basa en una jerarquía de contenedores con bordes suavizados y elevación tonal.

### A. Tarjetas Informativas (Cards)
- **Radios de Curvatura:** Se utilizan radios generosos de **20dp a 24dp**.
- **Estados de Permiso:**
  - **Activo:** Usa `primaryContainer` con opacidad reducida (`alpha = 0.4f`) e iconos en verde (`CheckCircle`).
  - **Inactivo/Error:** Usa `errorContainer` con opacidad reducida e iconos en rojo (`Warning`/`Info`).
- **Contenedores Tonalizados:** Uso de `surfaceVariant` con alpha para elementos secundarios como la "Lista blanca".

### B. Navegación y Gestos
- **Edge-to-Edge:** La actividad utiliza `enableEdgeToEdge()`, integrando el contenido detrás de las barras de estado y navegación del sistema.
- **Scroll Elástico:** Implementado mediante `verticalScroll` para garantizar accesibilidad en pantallas pequeñas.
- **Hojas Inferiores (Bottom Sheets):** Uso de `ModalBottomSheet` con `tonalElevation = 8.dp` para desgloses detallados (Diagnóstico y Lista de Apps).

## 3. Recursos Gráficos (Assets)

### A. Iconografía (Material Symbols)
Se utilizan iconos vectoriales para una escalabilidad perfecta:
- **Estado:** `CheckCircle` (Correcto), `Warning` (Acción requerida), `Info` (Informativo).
- **Acción:** `KeyboardArrowRight` (Entrada), `List` (Selección).
- **Específicos:** `notification_settings_24px` y `battery_profile_24px` (Contexto de sistema).

### B. Identidad Visual de Aplicaciones
- **Iconos Dinámicos:** La lista de aplicaciones musicales extrae los iconos directamente del `PackageManager` en tiempo de ejecución.
- **Normalización:** Los iconos de las apps se renderizan dentro de círculos (`CircleShape`) con un tamaño estándar de **48dp** para mantener la uniformidad visual.

## 4. Tokens de Diseño (Dimens)

Basados en el archivo [dimens.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/values/dimens.xml):
- **Padding General:** 20dp (horizontal) en la columna principal.
- **Spacers:** Uso de espacios fijos de **16dp, 32dp, 48dp y 64dp** para crear aire y separación visual.
- **Altura de Items:** Los elementos de ajuste (`SettingsItem`) mantienen un padding interno de **16dp** para una zona de toque cómoda.

---
*Documento generado para referencia de diseño UI/UX.*
