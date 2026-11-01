package tools.devhub.querydesk.datagrip

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.IdeFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a project's QueryDesk data sources to what the member can use without them running
 * Connect QueryDesk again: at startup, every few minutes, and when they come back to the IDE.
 * Only a signed-in IDE syncs; it never opens the browser.
 */
@Service(Service.Level.PROJECT)
class QueryDeskSync(private val project: Project, private val scope: CoroutineScope) {
  private val running = AtomicBoolean()

  @Volatile
  private var lastRun = 0L

  fun start() {
    scope.launch {
      while (isActive) {
        sync()
        delay(INTERVAL)
      }
    }
  }

  /** A sync now, unless one ran moments ago. */
  fun syncSoon() {
    if (System.currentTimeMillis() - lastRun >= MIN_GAP.inWholeMilliseconds) scope.launch { sync() }
  }

  private suspend fun sync() {
    if (!running.compareAndSet(false, true)) return
    try {
      lastRun = System.currentTimeMillis()
      val auth = QueryDeskAuth.getInstance()
      for (server in withContext(Dispatchers.EDT) { QueryDeskDataSources.servers(project) }) {
        // signed out of that QueryDesk, or it's unreachable: its data sources stay as they are
        val databases = withContext(Dispatchers.IO) {
          runCatching { auth.accessToken(server)?.let { QueryDeskDataSources.list(server, it) } }
            .onFailure { LOG.info("couldn't list $server's QueryDesk databases: ${it.message}") }
            .getOrNull()
        } ?: continue
        withContext(Dispatchers.EDT) { if (!project.isDisposed) QueryDeskDataSources.reconcile(project, server, databases) }
      }
    } finally {
      running.set(false)
    }
  }

  companion object {
    private val LOG = logger<QueryDeskSync>()
    private val INTERVAL = 5.minutes
    private val MIN_GAP = 30.seconds
  }

  class Start : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<QueryDeskSync>().start()
  }

  /** Coming back to the IDE, from opening a PR say, picks up its database. */
  class OnActivation : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
      ideFrame.project?.takeUnless { it.isDisposed }?.service<QueryDeskSync>()?.syncSoon()
    }
  }
}
