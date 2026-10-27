pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        // NOTE (Mihon Gold build environment): Gradle's HTTP client cannot
        // complete downloads from dl.google.com here — the connection is
        // dropped right after the TLS handshake, so the Google repository
        // gets disabled and any marker that lives only there fails with
        // "Plugin ... was not found". Workarounds, in order:
        //  - The Crashlytics marker (Google-only, and missing from mirrors)
        //    is vendored under gradle/vendor-repo (single POM, fetched from
        //    dl.google.com with curl, which works fine).
        //  - All other Android markers come from the Aliyun mirror of
        //    Google's Maven repo, which Gradle CAN reach.
        //  - Everything else comes from the plugin portal / Central.
        // google() is deliberately absent here: it would only fail.
        maven(url = uri("gradle/vendor-repo")) {
            content {
                includeGroup("com.google.firebase.crashlytics")
                includeGroup("com.google.gms.google-services")
            }
        }
        maven(url = "https://maven.aliyun.com/repository/google") {
            content {
                // Marker groups AND implementation groups: plugin
                // implementations resolve through these same repositories.
                includeGroupByRegex("""com\.android.*""")
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.google.*""")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        // Mihon Gold build environment: dl.google.com is unreachable from
        // Gradle's HTTP client here (its MITM certificate fails Java PKIX
        // validation, which disables the repo and poisons the build), so
        // google() is deliberately ABSENT from dependency repositories:
        // Android artifacts resolve from the Aliyun mirror instead.
        maven(url = uri("gradle/vendor-repo")) {
            content {
                includeGroup("com.google.firebase.crashlytics")
                includeGroup("com.google.gms.google-services")
                // Vendored artifacts live here when the Aliyun mirror has
                // only their POM (or serves them unreliably); a file repo
                // answers clean 404s for everything not vendored, so this
                // broad group cannot poison other resolutions.
                includeGroupByRegex("""androidx\..*""")
            }
        }
        maven(url = "https://maven.aliyun.com/repository/google")
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Mihon"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:metro")
include(":data")
include(":domain")
include(":i18n")
include(":icons:custom-material-symbols")
include(":icons:material-symbols")
include(":icons:simple-icons")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
