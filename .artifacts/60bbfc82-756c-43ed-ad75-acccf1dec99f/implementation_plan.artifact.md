# Plan de Acción: Simplificación Estructural y Lógica de Diseño (v3)

Este plan detalla la simplificación del componente `Layout4x4` (Large) para eliminar redundancias lógicas y consolidar el sistema de información de estado.

## User Review Required

> [!IMPORTANT]
> Se eliminará la variable `isShort` y sus dependencias. La apariencia **Large** pasará a ser estática en su estructura, confiando plenamente en el detector de colisiones global para cambiar de diseño si el espacio es insuficiente.

## Proposed Changes

### [Component Name] Interfaz de Usuario (Glance)

#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)

- **Simplificación de `Layout4x4`**:
    - Eliminar `val isShort`.
    - Eliminar condicionales basados en `isShort`.
    - El texto de estado (arriba a la derecha) será siempre visible en este layout.
    - La llamada a `TextInfo` dentro de este layout tendrá `showRelativeTime = false` de forma fija.
- **Limpieza de Rellenos (Paddings)**: Ajustar los márgenes de la columna de texto para que sean consistentes sin importar la altura (dentro del rango Large).

#### [MODIFY] [technical_specification_widget_status.artifact.md](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/.artifacts/technical_specification_widget_status.artifact.md)

- Actualizar la sección de UI para reflejar que la apariencia **Large** es el único layout donde el artista es persistente y el tiempo relativo vive exclusivamente en la zona de estado superior.

## Verification Plan

### Manual Verification
1. **Widget Large (2x4)**:
    - Verificar que el estado ("Escuchando", "En pausa", "Reciente") siempre aparezca arriba a la derecha.
    - Verificar que abajo siempre aparezca el **Artista** (o Letra si hay sesión activa), nunca el tiempo relativo redundante.
2. **Widget Standard (Pill)**:
    - Verificar que mantiene su comportamiento de "secuestro" del campo artista para mostrar el tiempo relativo en modo historial (comportamiento deseado para ahorrar espacio).
