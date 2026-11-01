// The driver on its own, as com.querydesk:querydesk-jdbc on Maven Central, for any JDBC tool.
// The plugin ships the same sources in its own jar (../build.gradle.kts).
plugins {
  `java-library`
  id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.querydesk"
// matches QueryDeskDriver's MAJOR_VERSION and MINOR_VERSION; release-driver.yml checks both
version = "0.1.0"

repositories { mavenCentral() }

tasks.withType<JavaCompile>().configureEach {
  options.release = 17
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// test.sh runs DriverTest, which is a main() against a stand-in server rather than JUnit
tasks.named<Jar>("jar") { archiveBaseName = "querydesk-jdbc" }

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  // the release workflow passes the key; a local publishToMavenLocal goes unsigned
  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

  coordinates("com.querydesk", "querydesk-jdbc", version.toString())

  pom {
    name = "QueryDesk JDBC driver"
    description = "Runs SQL through QueryDesk over HTTPS, as the signed-in member, with QueryDesk's reviews, data protection and audit log applied."
    url = "https://github.com/devhub-tools/querydesk-datagrip"
    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/licenses/MIT"
      }
    }
    developers {
      developer {
        id = "querydesk"
        name = "QueryDesk"
        email = "support@querydesk.com"
      }
    }
    scm {
      url = "https://github.com/devhub-tools/querydesk-datagrip"
      connection = "scm:git:https://github.com/devhub-tools/querydesk-datagrip.git"
    }
  }
}
