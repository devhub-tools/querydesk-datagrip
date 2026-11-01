package tools.devhub.querydesk.datagrip

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A project's QueryDesk data sources, kept to the credentials the member can use: one data
 * source per credential, grouped under QueryDesk and the database's group. Databases come and
 * go (a PR environment's lasts as long as its PR), so a credential that's gone takes its data
 * source with it. Data sources are added without being synced: an organization can have
 * hundreds of databases, so each loads its schema when the user opens it.
 */
object QueryDeskDataSources {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

  data class Changes(val added: Int, val removed: Int)

  /** The QueryDesk servers this project has data sources from. */
  fun servers(project: Project): Set<String> =
    LocalDataSourceManager.getInstance(project).dataSources
      .filter { isQueryDesk(it) }
      .map { QueryDeskServer.serverOf(it.url!!) }
      .toSet()

  /** The databases on [server] the token's member can use, each with its credentials. */
  fun list(server: String, token: String): List<JsonObject> {
    val request = HttpRequest.newBuilder(URI.create("$server/api/v1/querydesk/credentials"))
      .header("authorization", "Bearer $token")
      .header("accept", "application/json")
      .timeout(Duration.ofSeconds(30))
      .GET()
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) throw SignInException("QueryDesk answered ${response.statusCode()}")
    return Gson().fromJson(response.body(), JsonObject::class.java).getAsJsonArray("databases").map { it.asJsonObject }
  }

  /** Adds a data source for each new credential and removes those whose credential is gone. Call on the EDT. */
  fun reconcile(project: Project, server: String, databases: List<JsonObject>): Changes {
    val manager = LocalDataSourceManager.getInstance(project)
    val existing = manager.dataSources.filter { isQueryDesk(it) && QueryDeskServer.serverOf(it.url!!) == server }
    // by credential, so a data source outlives a change to the URL's other parameters
    val existingCredentials = existing.map { QueryDeskServer.credentialOf(it.url!!) }.toSet()
    val wanted = mutableSetOf<String>()
    var added = 0

    ApplicationManager.getApplication().runWriteAction {
      for (database in databases) {
        val adapter = database.get("adapter").asString
        if (!QueryDeskServer.supports(adapter)) continue
        val driver = DatabaseDriverManager.getInstance().getDriver(QueryDeskServer.driverId(adapter)) ?: continue
        val credentials = database.getAsJsonArray("credentials").map { it.asJsonObject }

        for (credential in credentials) {
          val credentialId = credential.get("id").asString
          wanted += credentialId
          if (credentialId in existingCredentials) continue

          val url = QueryDeskServer.jdbcUrl(server, credentialId, adapter)
          val name = database.get("name").asString
          val username = credential.get("username").asString
          val dataSource = LocalDataSource.create(if (credentials.size > 1) "$name ($username)" else name, driver.driverClass, url, "")
          dataSource.databaseDriver = driver
          dataSource.authProviderId = QueryDeskAuthProvider.ID
          dataSource.groupName = listOfNotNull("QueryDesk", database.get("group")?.takeUnless { it.isJsonNull }?.asString)
            .joinToString("/")
          // loads the schema when the user first opens it, not here
          dataSource.isAutoSynchronize = true
          manager.addDataSource(dataSource)
          added++
        }
      }

      for (stale in stale(existing, wanted)) manager.removeDataSource(stale)
    }
    return Changes(added, stale(existing, wanted).size)
  }

  private fun stale(existing: List<LocalDataSource>, wanted: Set<String>) =
    existing.filter { QueryDeskServer.credentialOf(it.url!!) !in wanted }

  private fun isQueryDesk(dataSource: LocalDataSource) =
    dataSource.authProviderId == QueryDeskAuthProvider.ID && dataSource.url?.startsWith(QueryDeskServer.URL_PREFIX) == true
}
