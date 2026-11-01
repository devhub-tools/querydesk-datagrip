package tools.devhub.querydesk.datagrip

import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseAuthProvider.ApplicabilityLevel
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "QueryDesk" choice under a data source's Authentication. On each connection it puts a
 * fresh QueryDesk access token where the driver reads its password, signing in in the browser
 * first when the IDE isn't signed in to that QueryDesk.
 */
class QueryDeskAuthProvider : DatabaseAuthProvider {
  override fun getId() = ID

  override fun getDisplayName() = "QueryDesk"

  // the only way to sign in to a QueryDesk data source, so it's also the default for one
  override fun getApplicability(point: DatabaseConnectionPoint, level: ApplicabilityLevel): ApplicabilityLevel.Result =
    if (point.databaseDriver?.id?.startsWith("querydesk-") == true) ApplicabilityLevel.Result.PREFERRED
    else ApplicabilityLevel.Result.NOT_APPLICABLE

  override suspend fun interceptConnection(proto: ProtoConnection, silent: Boolean): Boolean {
    val server = QueryDeskServer.serverOf(proto.url)
    val auth = QueryDeskAuth.getInstance()
    // a silent connection (a background refresh) never opens the browser
    val token = withContext(Dispatchers.IO) { auth.accessToken(server) ?: if (silent) null else auth.signIn(server) }
      ?: return false
    proto.connectionProperties[DatabaseAuthProvider.PASSWORD_KEY] = token
    return true
  }

  companion object {
    const val ID = "querydesk"
  }
}
