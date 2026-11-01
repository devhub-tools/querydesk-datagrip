plugins {
  id("org.jetbrains.kotlin.jvm") version "2.4.20"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "tools.devhub.querydesk"
version = "0.2.1-beta"

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
    // no upper bound: the verifier checks each new DataGrip before a release claims it
    ideaVersion {
      sinceBuild = "262"
      untilBuild = provider { null }
    }
    changeNotes = """
      <ul>
        <li>Connect QueryDesk adds a data source for every database credential you can use, grouped by QueryDesk group.</li>
        <li>Browser sign-in that stays signed in across restarts, with the refresh token in the IDE's password safe.</li>
        <li>PostgreSQL, MySQL, SQL Server, Oracle and ClickHouse.</li>
      </ul>
    """.trimIndent()
  }
  // set in the release workflow from the public repo's secrets
  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }
  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
    // 0.2.0-beta goes to the beta channel, 0.2.0 to the default (stable) one
    channels = providers.provider { listOf(version.toString().substringAfter('-', "default").substringBefore('.')) }
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
