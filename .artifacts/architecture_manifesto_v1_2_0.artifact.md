# Arquitectura de Widgets Adaptables y Desarrollo del Motor de Letras Sincronizadas Infalible con Optimización de Energía y Diseño UX (v1.2.0)

Este manifiesto técnico define los pilares arquitectónicos que convierten al **Music Widget** en una solución de ingeniería de primer nivel, equilibrando la fidelidad visual con el respeto absoluto por los recursos del sistema.

---

## 1. El Motor de Letras "Infalible" (Mathematical Sync Engine)

A diferencia de los reproductores convencionales que saturan el procesador con peticiones constantes (*polling*), nuestro motor implementa una **Sincronización Matemática Desacoplada**.

### A. La Fórmula del Tiempo Real
Utilizamos la última marca de tiempo conocida (`lastPositionUpdateTime`) y la proyectamos localmente:
$$\text{Posición} = \text{Base} + (\text{Tiempo Actual} - \text{Última Actualización})$$
Esto permite actualizaciones de letras a 2fps (500ms) con un consumo de CPU virtualmente nulo, ya que el cálculo es aritmético y ocurre dentro del proceso del widget.

### B. Ciclo de Vida de Energía (Battery Aware)
- **Modo Latente:** Al pausar, el motor reduce su frecuencia a 60s, alternando entre letra y artista.
- **Hard-Stop:** Tras 2 horas de inactividad total, el motor se apaga completamente para proteger la batería del usuario.

---

## 2. Arquitectura de Widgets Adaptables (Fluid UI)

El widget no es una imagen estática, es un componente inteligente que reacciona a su entorno mediante **Detección de Colisiones Geométricas**.

### A. Sensor de Colisión de Texto
Calculamos dinámicamente el espacio necesario según el `fontScale` del usuario:
- Si el texto "empuja" a la carátula, el widget muta automáticamente a un layout **Full Bleed** (Inmersivo).
- Esto garantiza accesibilidad universal: el widget nunca se "rompe", se adapta.

### B. Diseño UX: La Píldora Atómica
Implementamos una transformación de imagen quirúgica en `ImageUtils`:
- Rotación de -28° con recorte de transparencia atómico.
- Sincronización mediante llaves digitales (`.key`) que evitan el parpadeo visual al cambiar de pista.

---

## 3. Persistencia de Sesión y Snapshot (UX Ininterrumpida)

Hemos eliminado el estado de "vacío" mediante la **Sesión Latente**.

### A. Snapshots Persistentes
Cuando la sesión del sistema muere, el widget captura un Snapshot inmutable en el `DataStore`. El usuario siempre ve su última canción escuchada, proporcionando una sensación de "memoria" que los widgets estándar de Android no poseen.

### B. Regla de Promoción de Sesión (Session Promotion)
Implementamos una guardia de seguridad que solo permite que una nueva app tome el control si está en estado `PLAYING`. Esto protege al widget de "ruido" de aplicaciones de sistema (como notificaciones de KDE Connect o Mapas).

---

## 4. Integridad de Datos y Llaves Digitales

El sistema de **Llaves Digitales** (`trackKey`) garantiza la coherencia atómica:
- **Identidad única:** `Package|Title|Artist|Album|DurationMs`.
- **Validación en Disco:** Antes de renderizar cualquier imagen, el widget verifica que la llave en memoria coincida con la llave en el archivo. Si no coinciden, se muestra un placeholder, evitando CUALQUER cruce de información entre apps.

---

## Conclusión

La versión **1.2.0** marca la madurez del proyecto, pasando de ser un simple visualizador a un subsistema de medios robusto que imita y mejora las prácticas de **SystemUI** de Android.

> [!IMPORTANT]
> Este documento sirve como especificación maestra para todas las futuras expansiones del proyecto.
