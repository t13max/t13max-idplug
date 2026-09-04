plugins {
    id("java")
}

group = "com.t13max.idplug"
version = "1.0-SNAPSHOT"

// 为所有项目设置仓库
allprojects {
    repositories {
        mavenCentral()
    }
}

// 为所有子项目设置通用配置
subprojects {
    apply(plugin = "java") // 如果是 Kotlin 项目，改为 "kotlin"

    // 微信模块独立声明依赖，避免打包旧日志框架和不需要的 Lombok。
    if (name != "t13max-idplug-wechat") {
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.10.0"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            implementation("org.apache.logging.log4j:log4j-api:2.17.2")
            implementation("org.apache.logging.log4j:log4j-core:2.17.2")
            implementation("org.apache.logging.log4j:log4j-api:2.17.2")
            implementation("org.projectlombok:lombok:1.18.30")
        }
    }
}


tasks.test {
    useJUnitPlatform()
}

