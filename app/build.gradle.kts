plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.marlonpya.pos"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.marlonpya.pos"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Desde el emulador, 10.0.2.2 es la máquina donde corren gateway-mock y el simulador.
        buildConfigField("String", "GATEWAY_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "GATEWAY_PORT", "8583")
        buildConfigField("String", "GATEWAY_ADMIN_URL", "\"http://10.0.2.2:8080\"")
        buildConfigField("String", "PINPAD_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "PINPAD_PORT", "9100")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.lab.iso8583)
    implementation(libs.lab.pinpad)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Control de PCI en el build: falla si en el código de la app aparece un
 * número con forma de PAN (13 a 19 dígitos que pasan Luhn) o un log que
 * mencione PAN, pista 2 o PIN. Convierte "no logueamos datos de tarjeta" en
 * algo que el pipeline verifica solo, en cada compilación.
 */
val pciScan by tasks.registering {
    group = "verification"
    description = "Busca PAN literales y logs de datos sensibles en src/main."
    val sources = layout.projectDirectory.dir("src/main/java").asFile
    inputs.dir(sources)
    doLast {
        val panLiteral = Regex("""(?<![0-9])[0-9]{13,19}(?![0-9])""")
        val sensitiveLog = Regex("""(Log\.[vdiwe]|println|print)\s*\(.*(track2|pinBlock|\bpan\b|\bpin\b)""", RegexOption.IGNORE_CASE)
        fun luhn(s: String): Boolean {
            var sum = 0
            s.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
                sum += d
            }
            return sum % 10 == 0
        }
        val findings = mutableListOf<String>()
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { n, line ->
                if ("pci:allow" in line) return@forEachIndexed
                panLiteral.findAll(line).filter { luhn(it.value) }.forEach {
                    findings += "${file.name}:${n + 1}: número con forma de PAN"
                }
                if (sensitiveLog.containsMatchIn(line)) findings += "${file.name}:${n + 1}: log con dato sensible"
            }
        }
        if (findings.isNotEmpty()) {
            throw GradleException("pciScan encontró ${findings.size} problema(s):\n" + findings.joinToString("\n"))
        }
    }
}

tasks.named("preBuild") { dependsOn(pciScan) }
