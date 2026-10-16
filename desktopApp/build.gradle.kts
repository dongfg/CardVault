import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val appVersion = "1.0.0"
val sqlitePlatform = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "mac"
    System.getProperty("os.name").startsWith("Linux") -> "linux"
    else -> error("Unsupported desktop platform for SQLite: ${System.getProperty("os.name")}")
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(variantOf(libs.sqlite.jdbc) { classifier("without-natives") })
    runtimeOnly(variantOf(libs.sqlite.jdbc) { classifier("natives-$sqlitePlatform") })

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.dongfg.cardvault.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            // Shrink unused code; avoid bytecode rewriting around Compose/JNI.
            optimize.set(false)
            joinOutputJars.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "CardVault"
            packageVersion = appVersion
            description = "A personal card collection"
            vendor = "dongfg"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            modules("java.desktop", "java.logging", "java.instrument", "java.sql", "jdk.unsupported")
            windows {
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                upgradeUuid = "fb338c96-f46d-4fc3-b38d-8c7650f345ae"
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

// Run the storage regression suite against the actual shrunk application JARs.
tasks.register<Test>("testRelease") {
    group = "verification"
    description = "Test ProGuard output and platform SQLite libraries without packaging"
    dependsOn("proguardReleaseJars", "testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    val releaseJars = tasks.named("proguardReleaseJars").map {
        it.outputs.files.asFileTree.matching { include("**/*.jar") }
    }
    classpath = sourceSets["test"].output + files(releaseJars) +
        (configurations.testRuntimeClasspath.get() - configurations.runtimeClasspath.get())
    javaLauncher.set(tasks.named<Test>("test").flatMap { it.javaLauncher })
}

if (System.getProperty("os.name").startsWith("Windows")) {
    val architecture = when (val arch = System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x64"
        "aarch64" -> "arm64"
        else -> arch
    }
    val portableZip = tasks.register<Zip>("packagePortableZip") {
        group = "distribution"
        description = "Build a Windows portable ZIP including the Java runtime"
        dependsOn("createReleaseDistributable")
        from(layout.buildDirectory.dir("compose/binaries/main-release/app/CardVault")) {
            into("CardVault")
        }
        from(rootProject.layout.projectDirectory.file("LICENSE")) {
            into("CardVault")
        }
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set("CardVault-$appVersion-windows-$architecture.zip")
    }

    val source = layout.projectDirectory.file("packaging/windows/SingleFileLauncher.cs").asFile
    val manifest = layout.projectDirectory.file("packaging/windows/launcher.manifest").asFile
    val payload = layout.buildDirectory.file("distributions/CardVault-$appVersion-windows-$architecture.zip").get().asFile
    val hashFile = layout.buildDirectory.file("single-file/payload.sha256").get().asFile
    val singleExe = layout.buildDirectory.file("distributions/CardVault-$appVersion-windows-$architecture-single.exe").get().asFile
    val framework = File(System.getenv("WINDIR") ?: "C:/Windows", "Microsoft.NET/Framework64/v4.0.30319")
        .let { if (File(it, "csc.exe").exists()) it else File(it.parentFile.parentFile, "Framework/v4.0.30319") }

    tasks.register<Exec>("packageSingleExe") {
        group = "distribution"
        description = "Build one self-extracting Windows EXE with a reusable runtime cache"
        dependsOn(portableZip)
        inputs.files(source, manifest, payload)
        outputs.file(singleExe)
        doFirst {
            hashFile.parentFile.mkdirs()
            singleExe.parentFile.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            payload.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            hashFile.writeText(digest.digest().joinToString("") { "%02x".format(it) })
        }
        commandLine(
            File(framework, "csc.exe").absolutePath,
            "/nologo", "/target:winexe", "/platform:anycpu", "/optimize+", "/debug-", "/utf8output",
            "/out:${singleExe.absolutePath}", "/win32manifest:${manifest.absolutePath}",
            "/reference:${File(framework, "System.IO.Compression.dll").absolutePath}",
            "/reference:${File(framework, "System.Windows.Forms.dll").absolutePath}",
            "/resource:${payload.absolutePath},CardVault.Payload",
            "/resource:${hashFile.absolutePath},CardVault.PayloadHash",
            source.absolutePath,
        )
    }
}
