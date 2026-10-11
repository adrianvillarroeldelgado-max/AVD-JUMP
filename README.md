# AVD jump (Android)

App nativa en Kotlin para medir **CMJ**, **CMJ con brazos (Abalakov)** y **Drop Jump** por tiempo de vuelo, grabando en cámara lenta (240 fps) con la cámara del teléfono.

## Qué hace

- **Requisitos:** Android 9 o superior; compilada para Android 16 (API 36), como exige Google Play desde agosto de 2026.
- **Grabar salto:** usa la sesión de alta velocidad de Camera2 (`createConstrainedHighSpeedCaptureSession`). Elige sola la mejor combinación de la cámara trasera y prefiere 240 fps a 1280×720. Si el teléfono no ofrece cámara lenta a otras apps, graba al máximo fps disponible y lo avisa.
- **Analizar video de la galería:** abre cualquier video grabado con la cámara lenta del sistema y pregunta a cuántos fps se grabó.
- **Análisis cuadro a cuadro:**
  - Barra de posición, botones ±1 y ±10, y zoom 1×/2×/3× centrado donde toques.
  - Las teclas de volumen avanzan o retroceden un cuadro.
  - Se marcan el despegue y el aterrizaje; en el Drop Jump también el contacto inicial.
- **Resultados:**
  - Altura (h = g·t²/8), tiempo de vuelo y velocidad de despegue.
  - En el Drop Jump: tiempo de contacto y RSI.
  - Si hay masa: potencia pico de Sayers.
- **Clientes y grupos:** ficha con nombre, grupo, fecha de nacimiento (edad), sexo, peso, estatura, deporte, posición, pierna dominante, medidas de pierna para el perfil F-V y notas. Se elige el cliente antes de medir; el historial y el CSV se filtran por grupo.
- **Historial de evaluaciones por cliente:** cada día de medición es una evaluación. Gráfico de evolución por indicador (CMJ, CMJ con brazos, SJ, RSI, asimetría, saltos repetidos, Pmax y perfil F-V) y comparación con la evaluación anterior (▲ mejora, ▼ empeora). Se comparte como texto.
- **Pruebas:** CMJ, CMJ con brazos, Squat Jump (con o sin carga), Drop Jump, CMJ a una pierna (izquierda y derecha) y saltos repetidos.
- **Detección automática (240 fps):**
  - Al grabar, se arrastra la línea naranja hasta donde apoyan los pies. Al detener, la app analiza esa franja en todos los cuadros y coloca despegue, aterrizaje y contacto (Drop Jump) o todas las marcas de los saltos repetidos.
  - Con videos de la galería, se pulsa **Auto** y se toca el suelo bajo los pies.
  - Las marcas siempre se pueden revisar y corregir a mano cuadro a cuadro.
  - El algoritmo compara la franja con un cuadro sin pies y mide qué parte está ocupada. No depende de dónde aterrice el pie y compensa el parpadeo de luces LED.
- **Asimetrías:** con CMJ a una pierna, (mejor − peor) / mejor × 100 (Bishop et al. 2018). Al guardar la segunda pierna se muestra la asimetría del día. Umbrales: <10 % normal, 10–15 % vigilar, >15 % intervenir.
- **Saltos repetidos:** se marcan en orden despegue, aterrizaje, despegue… Da altura, vuelo, contacto y RSI de cada salto, las medias y la caída de rendimiento (3 primeros vs 3 últimos).
- **Perfil fuerza-velocidad vertical** (método simple de Samozino 2008), como My Jump Lab:
  - Es una batería de medición más del selector de pruebas: **Perfil fuerza-velocidad (4 cargas)**.
  - Protocolo a elección: squat jump con carga o CMJ con carga. La app propone 4 cargas (0, 25, 50 y 75 % de la masa), editables.
  - Un video por carga, grabado a 240 fps o de la galería, con detección automática. Al guardar cada carga ofrece pasar a la siguiente; con las 4 abre el perfil.
  - Usa el peso, el largo de pierna y la altura a 90° de la ficha del cliente (hpo).
  - Calcula F0, V0, Pmax, pendiente real y óptima (modelo de Samozino 2012), desequilibrio F-V, R² y salto teórico actual y óptimo.
  - Muestra un gráfico con la recta real y la óptima.
  - Clasifica el perfil: déficit de fuerza alto (<60 % del óptimo) o leve (60–90 %), equilibrado (90–110 %), déficit de velocidad leve (110–140 %) o alto (>140 %).
  - Sugerencia de entrenamiento: el programa de Jiménez-Reyes, Samozino, Brughelli y Morin (2017), base de My Jump 2 y My Jump Lab. Cada semana, 6 ejercicios de 3 series repartidos entre fuerza, fuerza-potencia, potencia, potencia-velocidad y velocidad según el déficit (p. ej. déficit de fuerza alto: 3-2-1-0-0; déficit de velocidad alto: 0-0-1-2-3), con los ejercicios y cargas del estudio, 2 sesiones por semana durante 9 semanas.
- **Informe y recomendaciones:** resumen por atleta y sugerencias priorizadas (perfil F-V, asimetría, RSI, relación CMJ/SJ, uso de brazos, fatiga en saltos repetidos). Se comparte como texto.
- **Historial:**
  - Filtro por atleta con mejor CMJ, mejor CMJ brazos, % de uso de brazos y mejor RSI.
  - Exporta en CSV para compartir por WhatsApp, correo, Drive, etc.

Cada cuadro del video cuenta como un cuadro capturado, así que la medición es correcta aunque el sistema guarde el video de cámara lenta reproducido a 30 fps.

## Publicar para descarga

Ver **PUBLICAR.md**: GitHub Releases con APK firmado, enlace fijo a la última versión y página de descarga en GitHub Pages.

## Obtener el APK de prueba (GitHub Actions)

1. Crea una cuenta gratuita en github.com y un repositorio nuevo.
2. Sube el contenido de esta carpeta: **Add file › Upload files**, arrastra todo, incluida la carpeta `.github`.
3. Entra a la pestaña **Actions**. El flujo **Compilar APK** arranca solo (tarda unos 4–6 min).
4. Al terminar, abre la ejecución y descarga el artefacto **AVD-jump-apk** (un .zip con el APK).
5. Pasa el APK al teléfono, ábrelo y permite “instalar apps de origen desconocido”.

> Si GitHub no muestra la carpeta `.github` al subir desde el navegador, crea el archivo a mano: **Add file › Create new file**, ruta `.github/workflows/build-apk.yml`, y pega su contenido.

## Compilar con Android Studio

1. Abre la carpeta `AVDJump` con Android Studio (Koala o más nuevo).
2. Espera la sincronización de Gradle.
3. Conecta el teléfono con depuración USB y pulsa **Run**, o ve a **Build › Build APK(s)**.

Requisitos: Android 9 (API 28) o superior.

## Sobre los 240 fps en Android

Muchos teléfonos solo permiten la cámara lenta en su propia app de cámara. Samsung y Xiaomi, por ejemplo, a menudo no la exponen a otras apps. En ese caso la pantalla de inicio lo indica. Graba con la cámara lenta del sistema y usa **Analizar video de la galería**: la precisión es la misma.

## Estructura

| Archivo | Función |
|---|---|
| `MainActivity.kt` | Elección de prueba, atleta, masa y cajón |
| `CaptureActivity.kt` | Vista previa y grabación de alta velocidad con Camera2 + MediaRecorder |
| `HighSpeed.kt` | Detección del mejor modo de cámara lenta |
| `AnalysisActivity.kt` | Visor cuadro a cuadro, marcas y cálculos |
| `FrameSource.kt` | Decodificación de cuadros por índice con caché |
| `HistoryActivity.kt` | Historial, resumen por atleta y CSV |
| `Model.kt` | Fórmulas, modelo de datos y almacenamiento |
