pluginManagement {
    repositories {
        // 阿里云国内镜像(优先)
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // 官方源兜底(镜像缺失时仍可构建)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云国内镜像(优先)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // 官方源兜底(镜像缺失时仍可构建)
        google()
        mavenCentral()
    }
}
rootProject.name = "AssetStudioMobile"
include(":app")
