package tools.devhub.querydesk.datagrip

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Signs in to QueryDesk if needed and adds a data source for every credential the member can
 * use, grouped under QueryDesk and the database's group. One already added is left as it is.
 */
class AddDatabasesAction : AnAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = event.project != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val properties = PropertiesComponent.getInstance()
    val server = Messages.showInputDialog(
      project,
      "QueryDesk address:",
      "Connect QueryDesk",
      null,
      properties.getValue(SERVER_KEY, DEFAULT_SERVER),
      null,
    )?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return
    properties.setValue(SERVER_KEY, server)

    object : Task.Backgroundable(project, "Adding QueryDesk databases", true) {
      private lateinit var databases: List<JsonObject>

      override fun run(indicator: ProgressIndicator) {
        val auth = QueryDeskAuth.getInstance()
        indicator.text = "Signing in to QueryDesk in your browser…"
        val token = auth.accessToken(server) ?: auth.signIn(server)
        indicator.text = "Listing your QueryDesk databases…"
        databases = listCredentials(server, token)
      }

      override fun onSuccess() {
        val added = addDataSources(project, server, databases)
        notify(project, "Added $added QueryDesk data source${if (added == 1) "" else "s"}.", NotificationType.INFORMATION)
      }

      override fun onThrowable(error: Throwable) {
        notify(project, "Couldn't add QueryDesk databases: ${error.message}", NotificationType.ERROR)
      }
    }.queue()
  }

  private fun listCredentials(server: String, token: String): List<JsonObject> {
    val request = HttpRequest.newBuilder(URI.create("$server/api/v1/querydesk/credentials"))
      .header("authorization", "Bearer $token")
      .header("accept", "application/json")
      .GET()
      .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) throw SignInException("QueryDesk answered ${response.statusCode()}")
    return Gson().fromJson(response.body(), JsonObject::class.java).getAsJsonArray("databases").map { it.asJsonObject }
  }

  private fun addDataSources(project: Project, server: String, databases: List<JsonObject>): Int {
    val manager = LocalDataSourceManager.getInstance(project)
    val existing = manager.dataSources.map { it.url }.toSet()
    var added = 0

    ApplicationManager.getApplication().runWriteAction {
      for (database in databases) {
        val adapter = database.get("adapter").asString
        if (!QueryDeskServer.supports(adapter)) continue
        val driver = DatabaseDriverManager.getInstance().getDriver(QueryDeskServer.driverId(adapter)) ?: continue
        val credentials = database.getAsJsonArray("credentials").map { it.asJsonObject }

        for (credential in credentials) {
          val url = QueryDeskServer.jdbcUrl(server, credential.get("id").asString, adapter)
          if (url in existing) continue

          val name = database.get("name").asString
          val username = credential.get("username").asString
          val dataSource = LocalDataSource.create(
            if (credentials.size > 1) "$name ($username)" else name,
            driver.driverClass,
            url,
            "",
          )
          dataSource.databaseDriver = driver
          dataSource.authProviderId = QueryDeskAuthProvider.ID
          // loads the schema when the user first opens it, not here: an organization can have
          // hundreds of databases
          dataSource.isAutoSynchronize = true
          dataSource.groupName = listOfNotNull("QueryDesk", database.get("group")?.takeUnless { it.isJsonNull }?.asString)
            .joinToString("/")
          manager.addDataSource(dataSource)
          added++
        }
      }
    }
    return added
  }

  companion object {
    const val SERVER_KEY = "tools.devhub.querydesk.server"
    const val DEFAULT_SERVER = "https://app.querydesk.com"

    fun notify(project: Project?, message: String, type: NotificationType) {
      NotificationGroupManager.getInstance().getNotificationGroup("QueryDesk").createNotification(message, type).notify(project)
    }
  }
}
