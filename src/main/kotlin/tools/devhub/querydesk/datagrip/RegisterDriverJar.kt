package tools.devhub.querydesk.datagrip

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.ui.classpath.SingleRootClasspathElement
import java.nio.file.Path

/**
 * Points the QueryDesk driver definitions at the driver jar this plugin ships. Their XML can't
 * name it, since where the plugin is installed is only known at runtime.
 */
class RegisterDriverJar : ProjectActivity {
  override suspend fun execute(project: Project) {
    // this class is in <plugin>/lib/, and the driver is in <plugin>/driver/
    val pluginJar = PathManager.getJarPathForClass(RegisterDriverJar::class.java) ?: return
    val jar = VfsUtilCore.pathToUrl(Path.of(pluginJar).parent.resolveSibling("driver/querydesk-jdbc.jar").toString())
    val manager = DatabaseDriverManager.getInstance()

    for (driver in manager.drivers.filter { it.id.startsWith("querydesk-") }) {
      if (driver.additionalClasspathElements.any { it.classesRootUrls.contains(jar) }) continue
      driver.additionalClasspathElements = listOf(SingleRootClasspathElement(jar))
      manager.updateDriver(driver)
    }
  }
}
