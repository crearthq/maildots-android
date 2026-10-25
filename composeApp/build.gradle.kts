import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

// Yayın imzası (Play yükleme anahtarı): anahtar ve şifreler depo DIŞINDA, kullanıcının
// klasöründe (~/.maildots-signing/keystore.properties). Yoksa yayın derlemesi imzasız kalır.
val signingProps = Properties().apply {
    val f = file(System.getenv("MAILDOTS_SIGNING") ?: "${System.getProperty("user.home")}/.maildots-signing/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // iOS hedefleri tanımlıdır; derlemesi macOS gerektirir (Windows'ta atlanır).
    // İkinci değer: kvo.m'yi derleyen SDK ve clang hedefi.
    listOf(
        iosArm64() to ("iphoneos" to "arm64-apple-ios16.0"),
        iosSimulatorArm64() to ("iphonesimulator" to "arm64-apple-ios16.0-simulator"),
    ).forEach { (target, sdk) ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // Küçük ObjC yardımcısı (kvo.def + kvo.m): WKWebView.URL'in KVO ile izlenmesi,
        // web ayarlarında girişten sonra dönüş için. kvo.m statik kitaplık olarak derlenir.
        val kvoSource = project.file("src/nativeInterop/cinterop/kvo.m")
        val kvoDir = project.layout.buildDirectory.dir("kvo/${target.name}").get().asFile
        val buildKvo = project.tasks.register<Exec>("buildKvo${target.name.replaceFirstChar { it.uppercase() }}") {
            onlyIf { System.getProperty("os.name").startsWith("Mac") }
            inputs.file(kvoSource)
            outputs.dir(kvoDir)
            doFirst { kvoDir.mkdirs() }
            commandLine(
                "sh", "-c",
                "xcrun --sdk ${sdk.first} clang -target ${sdk.second} -fobjc-arc -O2 -c \"$kvoSource\" -o \"$kvoDir/kvo.o\"" +
                    " && xcrun libtool -static -o \"$kvoDir/libmdkvo.a\" \"$kvoDir/kvo.o\"",
            )
        }
        target.compilations.getByName("main").cinterops.create("kvo") {
            extraOpts("-libraryPath", kvoDir.absolutePath)
            project.tasks.named(interopProcessingTaskName) { dependsOn(buildKvo) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Hesap servisi (webmail API) istemcisi: Android ve iOS ortak.
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            // Anlık bildirim (Firebase Cloud Messaging).
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            // Play servisleri eski bir Fragment sürümü getirir; dosya seçici (Activity Result) güncelini ister.
            implementation("androidx.fragment:fragment:1.8.5")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // Yalnız yerel webmail entegrasyon testi (WebMailEngineIT) için deneme posta sunucusu.
                implementation(libs.greenmail)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "io.maildots.app.resources"
}

android {
    namespace = "io.maildots.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.maildots.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.1.0"
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
