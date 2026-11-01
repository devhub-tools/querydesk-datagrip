plugins {
  id("org.jetbrains.kotlin.jvm") version "2.4.20"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "tools.devhub.querydesk"
version = "0.1.9"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    datagrip("2026.2.6")
    bundledPlugin("com.intellij.database")
  }
}

kotlin {
  jvmToolchain(21)
  // implement DataGrip's Kotlin interfaces without copying their default methods into ours
  compilerOptions { jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY }
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion { sinceBuild = "262" }
  }
  // flags calls into DataGrip's internal or deprecated API, which can change between releases
  pluginVerification {
    ides { create("DB", "2026.2.6") }
  }
}

// The JDBC driver is built from jdbc-driver/ and shipped in the plugin, since DataGrip runs
// drivers in their own process from jar files. It targets Java 17 like its own build.
val driver: SourceSet = sourceSets.create("driver") {
  java.srcDir("jdbc-driver/src/main/java")
  resources.srcDir("jdbc-driver/src/main/resources")
}

tasks.named<JavaCompile>("compileDriverJava") { options.release = 17 }

val driverJar = tasks.register<Jar>("driverJar") {
  from(driver.output)
  archiveFileName = "querydesk-jdbc.jar"
}

tasks.prepareSandbox {
  from(driverJar) { into(pluginName.map { "$it/driver" }) }
}
