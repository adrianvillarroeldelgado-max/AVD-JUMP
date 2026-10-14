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

## 4. Publicar una versión (automático)

**Cada cambio que llega a la rama `main` publica una versión nueva sola.** GitHub compila, firma y publica en Releases (6–8 min) con el número `1.6.N`, donde `N` sube en cada publicación. Los cambios que solo tocan `docs/`, `play/` o archivos `.md` no publican versión.

En cada versión aparecen `AVD-jump.apk`, `AVD-jump-1.6.N.apk`, `AVD-jump-1.6.N-play.aab` (para Google Play) y `version.json` (lo consulta la app).

- **Actualización en los teléfonos:** desde la 1.6, la app instalada desde GitHub revisa al abrirse (como máximo cada 3 horas) si hay una versión nueva y ofrece **Actualizar**. Descarga el APK y Android pide confirmar con un toque. Se instala encima: no se pierden clientes ni saltos. También hay un botón **Buscar actualizaciones** al final de la pantalla de inicio.
- **La primera vez** Android pide activar "Permitir de esta fuente" para AVD jump. Solo una vez.
- **Quien tenga la 1.5.0** debe instalar la 1.6 a mano una vez (desde la página de descarga); de ahí en adelante se actualiza sola.
- **Probar sin publicar:** trabaja en otra rama. Cada cambio en una rama genera un APK de prueba en **Actions** (artefacto *AVD-jump-apk*) que se instala encima de la versión actual, pero no llega a los demás teléfonos hasta unirlo a `main`.
- **Cambiar 1.6 a 1.7:** edita el archivo `VERSION` en `main` (por ejemplo `1.7`).
- **Publicar a mano de nuevo la última versión:** pestaña **Actions › Publicar versión › Run workflow**.

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
