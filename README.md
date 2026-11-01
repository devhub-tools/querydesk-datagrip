# QueryDesk for DataGrip

A JetBrains plugin that connects DataGrip, and any JetBrains IDE with the Database tools, to the
databases you reach through [QueryDesk](https://querydesk.com). Every query runs in QueryDesk with
your access, reviews, data protection and the audit log applied, so the IDE never holds a
database password.

- **Connect QueryDesk**, in the Database Explorer's `+` menu (Tools → QueryDesk in IntelliJ IDEs),
  signs in in the browser and adds a data source for every database credential you can use,
  grouped under `QueryDesk/<group>`. Each one loads its schema the first time you open it.
- **Stays current**: databases you gain or lose in QueryDesk (a PR environment's, say) are added
  or removed on their own, at startup, every 5 minutes and when you switch back to the IDE. Only a
  signed-in IDE checks, and it never opens the browser.
- **Sign-in**: OAuth in the browser with PKCE. The refresh token is kept in the IDE's password
  safe (Keychain, Windows Credential Manager, or libsecret/KeePass), so you stay signed in across
  restarts. Each connection gets a fresh access token, and the driver renews it through the IDE
  when it expires, so a connection held open for hours keeps working.
- Rows stream as QueryDesk reads them: the first page shows without waiting for the rest, and
  closing a result stops its query.
- One driver per engine: PostgreSQL, MySQL, SQL Server, Oracle, ClickHouse and MongoDB. MongoDB's
  console takes the mongosh syntax QueryDesk runs, and its collections and fields come from the
  schema QueryDesk last synced.

## Build

```bash
mise exec -- ./gradlew buildPlugin
```

builds `build/distributions/querydesk-datagrip-<version>.zip`. Install it with Settings → Plugins
→ ⚙ → Install Plugin from Disk. `./gradlew verifyPlugin` checks it against DataGrip.

`jdbc-driver/` is the JDBC driver the plugin ships: no dependencies, Java 17, and each statement
goes to QueryDesk over HTTPS instead of to the database. See [its README](jdbc-driver/README.md).

## Release

Set `version` in `build.gradle.kts`, then push a matching tag (`v0.2.0-beta`). The Release
workflow verifies, signs and publishes to the JetBrains Marketplace: a `-beta` version goes to
the beta channel, a plain one to the default channel. It needs these repository secrets:

- `PUBLISH_TOKEN`: a JetBrains Marketplace token.
- `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`: the plugin signing certificate
  ([how to make one](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)).

The Marketplace takes a new plugin's first version only through its website upload.

The JDBC driver releases on its own. Set `MAJOR_VERSION` and `MINOR_VERSION` in
`QueryDeskDriver`, then push a `jdbc-v0.1.0` tag. The Release JDBC driver workflow builds and
tests it, and attaches `querydesk-jdbc-0.1.0.jar` to a GitHub release. It needs no secrets.

## Limitations

- Transactions are not supported yet; leave the transaction mode on Auto.

## Contributing

This repository is mirrored from the QueryDesk monorepo. Pull requests are welcome and are
brought back into the monorepo from here.

## License

[MIT](LICENSE)
