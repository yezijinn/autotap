plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.auto.fairy.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // :core 的全部逻辑走 JVM 单元测试（src/test/），无需 instrumented 测试
    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    // 禁止引入任何 Android/AndroidX 依赖——保持 :core 可脱离设备单测。
    // kotlinx-coroutines 等后续运行时依赖经架构评审后再加。
    testImplementation("junit:junit:4.13.2")
}
