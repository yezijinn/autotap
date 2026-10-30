import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 版本号 ───────────────────────────────────────────────
// versionCode 取构建日期（yyyyMMdd），天然单调递增；versionName 同期日期文本。
val buildDate = Date()
val versionCodeValue = SimpleDateFormat("yyyyMMdd", Locale.US).format(buildDate).toInt()
val versionNameValue = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(buildDate)

// ── 签名配置 ─────────────────────────────────────────────
// 优先读取环境变量（由 build_apk.py 注入），keystore.properties 仅作本地手动构建回退。
// 两者都不完整时不配置签名，release 仍可构建为未签名产物，不报错。
val keystoreProps = Properties()
val ksFile = rootProject.file("keystore.properties")
if (ksFile.exists()) {
    // 必须关闭流，否则 Gradle 守护进程会一直持有 fd，Windows 上等同锁文件
    ksFile.inputStream().use { keystoreProps.load(it) }
}

val signingStoreFile = System.getenv("JINN_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
    ?: keystoreProps.getProperty("storeFile")?.let(rootProject::file)
val signingStorePassword = System.getenv("JINN_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("storePassword")
val signingKeyAlias = System.getenv("JINN_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("keyAlias")
val signingKeyPassword = System.getenv("JINN_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("keyPassword")
val hasSigningConfig = signingStoreFile != null && signingStorePassword != null &&
    signingKeyAlias != null && signingKeyPassword != null

android {
    namespace = "com.auto.fairy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blackberry.keyboard"
        minSdk = 28
        targetSdk = 34
        versionCode = versionCodeValue
        versionName = versionNameValue
        // 只保留中文资源，砍掉依赖库的多语言翻译（material/appcompat 约数百 KB）
        resourceConfigurations += setOf("zh")
    }

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // V1 已无必要（minSdk 28），V2/V3 覆盖全部目标设备
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "kotlin-tooling-metadata.json",
        )
    }
}

dependencies {
    // 依赖最小化：新增依赖必须先过 2.5MB 体积门禁
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 脚本列表需要 RecyclerView（M2 唯一新增依赖，约 200KB）
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(project(":core"))

    // 仅测试期依赖，不进 APK
    testImplementation("junit:junit:4.13.2")
}
