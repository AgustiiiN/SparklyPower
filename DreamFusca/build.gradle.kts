import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compile(kotlin("stdlib-jdk8"))
    compileOnly("com.comphenix.protocol:ProtocolLib:4.6.0-SNAPSHOT")
    compile(files("../libs/paper_server.jar"))
    compile(files("../libs/DreamCore-shadow.jar"))
    compile(files("../libs/WorldGuard.jar"))
    compileOnly("com.github.TechFortress:GriefPrevention:16.17.1")
    compile(files("../libs/ChestShop.jar"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
