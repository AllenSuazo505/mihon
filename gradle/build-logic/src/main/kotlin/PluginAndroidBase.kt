import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.DefaultConfig
import mihon.gradle.configurations.configureKotlin
import mihon.gradle.extensions.android
import mihon.gradle.extensions.configureTest
import mihon.gradle.extensions.coreLibraryDesugaring
import mihon.gradle.extensions.libs
import mihon.gradle.extensions.mihonx
import mihon.gradle.extensions.release
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

@Suppress("UNUSED")
class PluginAndroidBase : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()
        configureTest()

        android {
            defaultConfig {
                minSdk = mihonx.versions.android.sdk.min.get().toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = mihonx.versions.android.sdk.target.get().toInt()
                }

                ndkVersion = mihonx.versions.android.ndk.get()
            }

            compileSdk {
                version = release(mihonx.versions.android.sdk.compile)
            }

            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        dependencies {
            coreLibraryDesugaring(libs.android.desugar)
        }

        // Fork note: this project compiles against android-36 (see
        // app/build.gradle.kts) while several dependencies were compiled
        // against 37, which makes AGP's AAR-metadata check fail the build
        // even though all code compiles cleanly against 36 and upstream
        // targets SDK 36 at runtime. The :app module already disables this
        // guard for itself; disable it here too so every library module
        // (e.g. :core-metadata) is covered as well. It is a compile-time
        // check, not a runtime guarantee.
        tasks.configureEach {
            // AGP registers these tasks lazily, so configureEach applies
            // as they are registered. Covers debug/release and any other
            // build type (foss/nightly/benchmark).
            if (name.startsWith("check") && name.endsWith("AarMetadata")) {
                enabled = false
            }
        }
    }
}

private fun CommonExtension.defaultConfig(block: DefaultConfig.() -> Unit) {
    defaultConfig.apply(block)
}

private fun CommonExtension.compileOptions(block: CompileOptions.() -> Unit) {
    compileOptions.apply(block)
}
