# Publicar AVD jump en Google Play

Requisitos vigentes (octubre de 2026):

- Las apps nuevas deben apuntar a **Android 16 (API 36)**. AVD jump 1.5.0 ya lo cumple.
- Se sube un archivo **.aab** (no el APK). Lo genera solo GitHub al crear una versión: `AVD-jump-1.5.0-play.aab`.
- Las **cuentas personales** creadas después del 13/11/2023 deben hacer una **prueba cerrada con al menos 12 testers durante 14 días seguidos** antes de pedir el acceso a producción.

Tiempo total aproximado: 3–4 semanas, la mayoría esperando la prueba cerrada y las revisiones de Google.

---

## 1. Crear la cuenta de desarrollador

1. Entra a [play.google.com/console](https://play.google.com/console) con tu cuenta de Google.
2. Elige **Cuenta personal** (o **Organización** si AVD tiene empresa registrada con número D-U-N-S: las organizaciones no necesitan la prueba de 12 testers).
3. Paga la tarifa única de **25 USD**.
4. Verifica tu identidad (documento) y un número de teléfono. Puede tardar unos días.

## 2. Crear la app

**Crear app** → nombre `AVD jump`, idioma predeterminado **Español (Latinoamérica)**, tipo **App**, **Gratis**, acepta las declaraciones.

## 3. Firma de la app

En **Prueba y publicación › Configuración › Integridad de la app**, deja activada la **Firma de apps de Play**. Google guarda la clave final; tu clave `avd-jump-release.jks` queda como **clave de subida**. Sigue guardándola: la necesitas para cada actualización.

## 4. Ficha de Play Store

**Crecimiento › Presencia en la tienda › Ficha principal.** Textos listos más abajo.

| Campo | Archivo o texto |
|---|---|
| Ícono | `icono-512.png` |
| Gráfico destacado | `grafico-destacado-1024x500.png` |
| Capturas de teléfono | mínimo 2 (recomendado 4–8). Haz capturas reales con la app instalada: Inicio, Análisis con resultado, Batería F-V, Perfil F-V, Historial de evaluaciones, Informe. |
| Categoría | **Deportes** |
| Correo de contacto | adrianvillarroeldelgado@gmail.com (o el correo de AVD) |
| Política de privacidad | `https://USUARIO.github.io/avd-jump/privacidad.html` |

No menciones otras apps (My Jump, etc.) en la ficha: Google lo rechaza por las normas de metadatos.

### Nombre (máx. 30)
```
AVD jump
```

### Descripción breve (máx. 80)
```
Mide CMJ, Drop Jump y perfil fuerza-velocidad con la cámara lenta del celular.
```

### Descripción completa (máx. 4000)
```
AVD jump mide la altura del salto vertical con la cámara lenta de tu teléfono, por tiempo de vuelo, cuadro a cuadro a 240 fps.

MEDICIÓN PRECISA
• Graba a 240 fps o analiza un video de la galería.
• Detección automática del despegue y el aterrizaje: arrastra la línea del suelo hasta los pies y la app marca los cuadros sola.
• Revisión manual cuadro a cuadro, con zoom y teclas de volumen para avanzar de a un cuadro (±4 ms).

PRUEBAS
• CMJ y CMJ con brazos (Abalakov)
• Squat jump, con o sin carga
• Drop Jump: tiempo de contacto e índice de fuerza reactiva (RSI)
• CMJ a una pierna: asimetría entre piernas
• Saltos repetidos: altura, contacto y RSI de cada salto y caída de rendimiento
• Perfil fuerza-velocidad: batería de 4 saltos con 4 cargas, en squat jump o CMJ

PERFIL FUERZA-VELOCIDAD
Calcula fuerza máxima (F0), velocidad máxima (V0), potencia máxima, pendiente real y óptima y desequilibrio fuerza-velocidad, con el método validado de Samozino. Clasifica el perfil (déficit de fuerza, equilibrado o déficit de velocidad) y propone un plan de entrenamiento semanal basado en el estudio de Jiménez-Reyes, Samozino y colaboradores (2017).

CLIENTES Y EVALUACIONES
• Fichas con edad, peso, estatura, deporte, posición, pierna dominante y notas.
• Grupos de clientes (equipos, categorías).
• Historial de evaluaciones con gráfico de evolución y comparación con la evaluación anterior.
• Informe con recomendaciones priorizadas: perfil fuerza-velocidad, asimetría, fuerza reactiva, uso de brazos y fatiga.
• Exporta a CSV y comparte informes por WhatsApp o correo.

PRIVACIDAD
Todo queda guardado en tu teléfono. La app no usa internet, no tiene publicidad ni registra tus datos.

Nota: algunos teléfonos solo permiten la cámara lenta en su propia app de cámara. En ese caso, graba con la cámara lenta del sistema y analiza el video desde la galería con la misma precisión.

Desarrollada por AVD Entrenamiento Físico Deportivo.
```

## 5. Contenido de la app (Política › Contenido de la app)

| Formulario | Respuesta |
|---|---|
| Política de privacidad | la URL de arriba |
| Anuncios | **No** contiene anuncios |
| Acceso a la app | **Todas las funciones están disponibles sin restricciones** (no hay inicio de sesión) |
| Clasificación de contenido | Cuestionario IARC, categoría **Utilidad, productividad, comunicación u otra**. Respuesta **No** a violencia, sexo, lenguaje, drogas, juegos de azar, interacción entre usuarios, ubicación y compras. Resultado esperado: apta para todos. |
| Público objetivo | **18 años o más** (la usan entrenadores). Así no aplica la política de Familias. |
| Seguridad de los datos | ¿Recopila o comparte datos? **No**. La app no tiene permiso de internet y todo queda en el teléfono. (Los datos que solo se procesan en el dispositivo no cuentan como recopilados.) |
| Apps de salud | Declara **Actividad física y fitness**; no es una app médica ni hace diagnósticos. |
| App de gobierno / funciones financieras / noticias | **No** |

## 6. Prueba cerrada (12 testers, 14 días)

1. **Prueba y publicación › Pruebas › Prueba cerrada** → crea un canal (p. ej. `Testers AVD`).
2. **Testers:** crea una lista de correo con al menos **12 correos de Gmail** (atletas, colegas, alumnos). Mejor 15–20 por si alguno se da de baja.
3. **Crear versión** → sube `AVD-jump-1.5.0-play.aab` (de la página de GitHub Releases) → nombre `1.5.0` → notas "Primera versión de prueba" → **Revisar y lanzar**.
4. Google revisa la versión (horas a pocos días). Luego copia el **enlace de participación** y envíalo a los testers: cada uno debe abrirlo, pulsar **Convertirse en tester** e instalar desde Play Store.
5. Deben seguir inscritos **14 días seguidos**. Pídeles que usen la app y te den comentarios: Google pregunta por esto al pedir producción.

## 7. Acceso a producción

Pasados los 14 días: **Panel › Solicitar acceso a producción**. Responde el cuestionario sobre la prueba (cuántos testers, qué comentarios recibiste, qué cambiaste). Revisión: unos 7 días.

Con el acceso aprobado: **Producción › Crear versión** → sube el mismo .aab (o uno nuevo) → **Países:** elige Bolivia y los que quieras → **Enviar a revisión**.

## Actualizaciones

1. Crea una versión nueva en GitHub con un número mayor (`v1.5.1`, `v1.6.0`…). El código de versión sube solo.
2. Descarga el nuevo `.aab` de GitHub Releases y súbelo en Play Console (Producción › Crear versión).
3. Los usuarios de Play Store reciben la actualización automáticamente.

Quienes instalaron el APK desde GitHub deben desinstalarlo antes de pasar a la versión de Play Store (Play firma la app con otra clave), y eso borra sus datos. Para tus propios dispositivos, conviene exportar el CSV antes.
