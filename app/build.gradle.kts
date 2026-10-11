plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de publicación: se toma de variables de entorno (secretos de GitHub) o de keystore.properties.
// Sin ellas, el APK "release" se firma con la clave de depuración (sirve para probar, no para actualizar).
val keystoreProps = java.util.Properties().apply {
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
        // En GitHub Releases el número de versión sale de la etiqueta (v1.3.0) y el código del número de ejecución.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 6
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.5.0"
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
