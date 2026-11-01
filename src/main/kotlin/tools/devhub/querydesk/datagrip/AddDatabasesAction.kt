package tools.devhub.querydesk.datagrip

import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Signs in to QueryDesk if needed and adds a data source for every credential the member can
 * use ([QueryDeskDataSources]). After that, [QueryDeskSync] keeps them current.
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
        databases = QueryDeskDataSources.list(server, token)
      }

      override fun onSuccess() {
        val (added, removed) = QueryDeskDataSources.reconcile(project, server, databases)
        val message = "Added $added QueryDesk data source${if (added == 1) "" else "s"}" +
          (if (removed > 0) ", removed $removed whose database is gone" else "") + "."
        notify(project, message, NotificationType.INFORMATION)
      }

      override fun onThrowable(error: Throwable) {
        notify(project, "Couldn't add QueryDesk databases: ${error.message}", NotificationType.ERROR)
      }
    }.queue()
  }

  companion object {
    const val SERVER_KEY = "tools.devhub.querydesk.server"
    const val DEFAULT_SERVER = "https://app.querydesk.com"

    fun notify(project: Project?, message: String, type: NotificationType) {
      NotificationGroupManager.getInstance().getNotificationGroup("QueryDesk").createNotification(message, type).notify(project)
    }
  }
}
