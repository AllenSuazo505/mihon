dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        // Mihon Gold build environment: dl.google.com fails Java PKIX
        // validation here, so google() is deliberately absent (see root
        // settings); Android artifacts come from the Aliyun mirror.
        maven(url = "https://maven.aliyun.com/repository/google")
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
        create("mihonx") {
            from(files("../mihon.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
