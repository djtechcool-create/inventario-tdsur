# RetroCam

App de cámara para Android con filtros que imitan fotos antiguas y de rollo.

## Funciones
- **Vista previa en vivo** con el filtro aplicado en la GPU (OpenGL ES), en cualquier Android 10+.
- **Modo VIDEO VHS**: graba video con look de cinta VHS (color corrido, baja resolución, banda de tracking, ruido de cabezas, nieve, líneas de barrido y textos PLAY/hora/fecha). Opción de cinta nueva o gastada. Los videos se guardan en `Películas/RetroCam`.
- **97 filtros de rollos reales** en 7 categorías: Color, Cine, Diapositiva, Blanco y negro, Instantánea, Desechables y vintage, y Experimental (Kodak Portra, Gold, Ektar, Kodachrome, Fujifilm Superia, Velvia, Provia, Agfa Vista, Konica, Ilford HP5, Delta, Tri-X, CineStill, Polaroid, Instax, LomoChrome, Aerochrome, Redscale y muchos más).
- Sonido y vibración al tomar fotos y grabar; el revelado ocurre en segundo plano.
- Emulación de rollo: curvas por canal, mezcla de tintes, saturación que cae en luces y sombras, suavidad, aberración cromática, bloom, halación, grano con color, viñeta, flash, fugas de luz, polvo y rayones.
- **Fecha estampada** naranja, al estilo `'26 11 17`.
- **Marcos**: escaneo (marco negro irregular de la película sobre el papel del escáner, con luz que se cuela por la orilla), instantánea y negativo de 35mm con perforaciones.
- **Editor** para aplicar los filtros a fotos de la galería, con control de intensidad y botón 🎲 para variar fugas, grano y polvo.
- Cada foto sale un poco distinta: las fugas de luz, el grano y el polvo cambian en cada disparo.
- Las fotos se guardan en `Imágenes/RetroCam`.

## Instalar
Descarga siempre la última versión desde este enlace fijo (se actualiza solo con cada cambio):

https://github.com/djtechcool-create/inventario-tdsur/releases/download/retrocam-latest/RetroCam.apk

Ábrelo en el teléfono; Android pedirá permiso para "instalar apps de origen desconocido".
Las versiones nuevas se instalan encima de la anterior.

Requiere Android 10 o superior.

## Compilar localmente
Abre la carpeta `retrocam/` en Android Studio, o ejecuta:
```
./gradlew assembleRelease
```

## Estructura
| Archivo | Qué hace |
|---|---|
| `film/FilmLook.kt` | Definición de los filtros (parámetros de cada rollo) |
| `film/FilmEngine.kt` | Motor de emulación de rollo (Kotlin puro, usa todos los núcleos) |
| `film/FilmRenderer.kt` | Foto final: motor + polvo, fecha y marco |
| `gl/GlProcessor.kt` | Procesa la cámara en la GPU (vista previa y video) |
| `assets/shaders/film.frag` | Shader del rollo en vivo |
| `assets/shaders/vhs.frag` | Shader del efecto VHS |
| `ui/CameraScreen.kt` | Pantalla de cámara (CameraX) |
| `ui/EditorScreen.kt` | Editor de fotos de la galería |
| `ui/Components.kt` | Carrusel de filtros, marcos y capas de la vista previa |

Para crear un filtro nuevo, agrega un `FilmLook` en `FilmLooks` y súmalo a la lista `all`.
