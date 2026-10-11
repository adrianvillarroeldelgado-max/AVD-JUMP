# Publicar AVD jump para descarga (APK)

Con estos pasos queda:

- un **enlace de descarga fijo** que siempre entrega la última versión:
  `https://github.com/USUARIO/REPOSITORIO/releases/latest/download/AVD-jump.apk`
- una **página de descarga** con tu logo: `https://USUARIO.github.io/REPOSITORIO/`
- el **APK de prueba** en cada cambio, como en las versiones anteriores (pestaña *Actions*).

Todo es gratis. Solo necesitas una cuenta de GitHub.

---

## 1. Crear la cuenta y el repositorio

1. Entra a [github.com/signup](https://github.com/signup) (sirve desde el teléfono). Usa tu correo, crea una contraseña y un nombre de usuario (por ejemplo `avd-entrenamiento`). Ese nombre aparecerá en el enlace de descarga.
2. Confirma el código que llega a tu correo y elige el plan **Free**.
3. Pulsa **+ › New repository**:
   - Nombre: `avd-jump`
   - **Public** (con un repositorio privado nadie podría descargar el APK)
   - No marques "Add a README"
   - **Create repository**

## 2. Subir el proyecto

**Opción A, recomendada desde el teléfono: que Claude lo suba.**
1. En Claude, conecta GitHub: **Configuración › Conectores › GitHub › Conectar**, y autoriza el repositorio `avd-jump`.
2. Dime tu nombre de usuario de GitHub y subo el proyecto completo.

**Opción B, desde una computadora.**
1. Descomprime `AVD-jump-android.zip`.
2. En el repositorio vacío pulsa **uploading an existing file** y arrastra **todo el contenido** de la carpeta `AVDJump` (no la carpeta en sí): `app`, `docs`, `gradle`, `play`, `.github`, `gradlew`, `build.gradle.kts`, etc.
3. Pulsa **Commit changes**.

> Si la carpeta `.github` no se sube desde el navegador: **Add file › Create new file**, escribe `.github/workflows/release.yml`, pega el contenido del archivo y guarda. Repite con `build-apk.yml`.

**No subas nunca** el archivo `.jks` ni el archivo de contraseñas. Van como secretos (paso 3).

## 3. Guardar la clave de firma como secretos

La clave de firma hace que cada versión nueva se instale **encima** de la anterior sin perder clientes ni saltos. Te la entregué aparte (`AVD-jump-firma.zip`).

En el repositorio: **Settings › Secrets and variables › Actions › New repository secret**. Crea estos cuatro:

| Nombre del secreto | Valor |
|---|---|
| `KEYSTORE_BASE64` | todo el contenido de `avd-jump-release.jks.base64` |
| `KEYSTORE_PASSWORD` | la contraseña de `CLAVES-NO-SUBIR.txt` |
| `KEY_ALIAS` | `avdjump` |
| `KEY_PASSWORD` | la misma contraseña |

Guarda el zip de la firma en un lugar seguro (por ejemplo, Google Drive personal). Si se pierde, las versiones nuevas no se podrán instalar encima: habrá que desinstalar la app y se perderán los datos del teléfono.

## 4. Publicar una versión

**Forma rápida:** cambia el número del archivo `VERSION` (por ejemplo de `1.5.0` a `1.5.1`) y guarda el cambio en `main`. GitHub compila, firma y publica la versión solo, y crea la etiqueta `v1.5.1`.

**Forma manual, desde Releases:**

1. En el repositorio: **Releases › Create a new release** (o *Draft a new release*).
2. En **Choose a tag** escribe `v1.5.0` y pulsa **Create new tag**.
3. Título: `AVD jump 1.5.0`. Pulsa **Publish release**.
4. Ve a **Actions**: el flujo **Publicar versión** compila y firma el APK y el AAB (6–8 min).
5. Al terminar, en la versión aparecen `AVD-jump.apk`, `AVD-jump-1.5.0.apk` y `AVD-jump-1.5.0-play.aab` (este último es para Google Play).

Para cada actualización repite este paso con un número mayor: `v1.5.1`, `v1.6.0`…
El número interno de versión se calcula solo, así que Android la reconoce como actualización.

## 5. Activar la página de descarga

1. **Settings › Pages**.
2. En *Build and deployment*: **Source: Deploy from a branch**, rama **main**, carpeta **/docs**. Guarda.
3. En 1–2 minutos estará en `https://USUARIO.github.io/REPOSITORIO/`.

Comparte ese enlace con tus clientes o atletas. El botón **Descargar APK** siempre entrega la última versión publicada.

## Instalar en el teléfono

1. Abre el enlace desde el teléfono y descarga el APK.
2. Ábrelo. Android pedirá permitir **Instalar apps desconocidas** para el navegador: acepta.
3. Pulsa **Instalar**.

Google Play Protect puede mostrar un aviso porque la app no viene de Play Store. Pulsa **Más detalles › Instalar de todas formas**.

---

## Google Play Store

La guía completa, los textos de la ficha, el ícono y el gráfico destacado están en la carpeta **play/** (`play/PLAY-STORE.md`). La política de privacidad que pide Google queda publicada en `https://USUARIO.github.io/avd-jump/privacidad.html` al activar GitHub Pages (paso 5).
