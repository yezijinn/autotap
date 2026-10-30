pluginManagement {
    repositories {
        // 国内直连 dl.google.com / repo.maven.apache.org 会超时，阿里云镜像前置；
        // 后面保留官方仓库，镜像缺包时仍可回退。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "autotap"
include(":app")

// :core —— 纯 Kotlin、零 Android 依赖的回放内核（model/compiler/runtime）。
// 它是唯一可被 JVM 单元测试覆盖的部分，因此禁止出现 android.* / androidx.* 依赖。
include(":core")
