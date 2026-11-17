# RetroCam

App de cámara para Android con filtros que imitan fotos antiguas y de rollo.

## Funciones
- **Vista previa en vivo** con el filtro aplicado. En Android 13+ usa un shader en la GPU con grano incluido; en Android 10 a 12 usa una matriz de color.
- **8 filtros**: Dorado 200, Retrato 400, Cine 800T, Verde 100, Desechable 98, Instantánea, Plata B&N y Sepia 1920.
- Efectos: curvas de color, tono dividido, desvanecido, grano, viñeta, halación, fugas de luz, polvo y rayones, y pérdida de nitidez.
- **Fecha estampada** naranja, al estilo `'26 11 17`.
- **Marcos**: instantánea (borde blanco) y negativo de 35mm con perforaciones.
- **Editor** para aplicar los filtros a fotos de la galería, con control de intensidad y botón 🎲 para variar fugas, grano y polvo.
- Cada foto sale un poco distinta: las fugas de luz, el grano y el polvo cambian en cada disparo.
- Las fotos se guardan en `Imágenes/RetroCam`.

## Instalar
1. Ve a la pestaña **Actions** del repositorio, abre la última ejecución de "RetroCam Android" y descarga el artefacto **RetroCam-apk**.
2. Descomprime el zip, copia `app-release.apk` al teléfono y ábrelo. Android pedirá permiso para "instalar apps de origen desconocido".

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
| `film/FilmRenderer.kt` | Procesa la foto final a resolución completa |
| `film/PreviewEffects.kt` | Shader AGSL para la vista previa en tiempo real |
| `ui/CameraScreen.kt` | Pantalla de cámara (CameraX) |
| `ui/EditorScreen.kt` | Editor de fotos de la galería |
| `ui/Components.kt` | Carrusel de filtros, marcos y capas de la vista previa |

Para crear un filtro nuevo, agrega un `FilmLook` en `FilmLooks` y súmalo a la lista `all`.
