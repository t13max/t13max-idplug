plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.t13max.idplug.wechat"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) { local(localIde.get()) } else { intellijIdea("2026.1") }
        pluginVerifier()
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(providers.gradleProperty("buildJavaVersion").getOrElse("21").toInt())
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    // 界面全部使用普通 Swing，无需表单字节码插桩。
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
    pluginVerification { ides { current() } }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }
    test { useJUnitPlatform() }
}
