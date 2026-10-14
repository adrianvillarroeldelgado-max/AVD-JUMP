import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de publicación: se toma de variables de entorno (secretos de GitHub) o de keystore.properties.
// Sin ellas, el APK "release" se firma con la clave de depuración (sirve para probar, no para actualizar).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")
val hasReleaseKey = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "com.salto240"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avd.jump"
        minSdk = 28          // necesario para leer cuadros por índice (getFrameAtIndex)
        targetSdk = 36
        // El código de versión lo calcula GitHub (minutos desde 2024 del último cambio), así siempre sube
        // y cada versión nueva se instala encima de la anterior. La 1.5.0 publicada tenía el código 103.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 6
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.6-dev"
        // Repositorio donde la versión de GitHub busca actualizaciones.
        val repo = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: "adrianvillarroeldelgado-max/AVD-JUMP"
        buildConfigField("String", "UPDATE_REPO", "\"$repo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // github: APK de descarga directa, se actualiza sola desde GitHub Releases.
    // play: paquete para Google Play (Play se encarga de las actualizaciones; sin permiso de internet).
    flavorDimensions += "canal"
    productFlavors {
        create("github") { dimension = "canal" }
        create("play") { dimension = "canal" }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
