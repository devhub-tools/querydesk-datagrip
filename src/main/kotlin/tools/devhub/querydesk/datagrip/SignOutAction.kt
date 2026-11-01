package tools.devhub.querydesk.datagrip

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** Forgets the IDE's QueryDesk sign-in, so the next connection asks for it again. */
class SignOutAction : AnAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = QueryDeskAuth.getInstance().isSignedIn(server())
  }

  override fun actionPerformed(event: AnActionEvent) {
    QueryDeskAuth.getInstance().signOut(server())
    AddDatabasesAction.notify(event.project, "Signed out of QueryDesk.", NotificationType.INFORMATION)
  }

  private fun server() =
    PropertiesComponent.getInstance().getValue(AddDatabasesAction.SERVER_KEY, AddDatabasesAction.DEFAULT_SERVER)
}
