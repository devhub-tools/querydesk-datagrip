# QueryDesk for DataGrip

A JetBrains plugin that connects DataGrip, and any JetBrains IDE with the Database tools, to the
databases you reach through [QueryDesk](https://querydesk.com). Every query runs in QueryDesk with
your access, reviews, data protection and the audit log applied, so the IDE never holds a
database password.

- **Connect QueryDesk**, in the Database Explorer's `+` menu (Tools → QueryDesk in IntelliJ IDEs),
  signs in in the browser and adds a data source for every database credential you can use,
  grouped under `QueryDesk/<group>`. Each one loads its schema the first time you open it.
- **Sign-in**: OAuth in the browser with PKCE. The refresh token is kept in the IDE's password
  safe (Keychain, Windows Credential Manager, or libsecret/KeePass), so you stay signed in across
  restarts, and each connection gets a fresh access token.
- One driver per engine: PostgreSQL, MySQL, SQL Server, Oracle and ClickHouse.

## Build

```bash
mise exec -- ./gradlew buildPlugin
```

builds `build/distributions/querydesk-datagrip-<version>.zip`. Install it with Settings → Plugins
→ ⚙ → Install Plugin from Disk. `./gradlew verifyPlugin` checks it against DataGrip.

`jdbc-driver/` is the JDBC driver the plugin ships: no dependencies, Java 17, and each statement
goes to QueryDesk over HTTPS instead of to the database. See [its README](jdbc-driver/README.md).

## Limitations

- An access token lasts an hour and is handed to the driver when a connection opens, so a
  connection held open longer has to reconnect.
- Transactions are not supported yet; leave the transaction mode on Auto.
- Results are held in memory, capped by the data source's max rows.

## Contributing

This repository is mirrored from the QueryDesk monorepo. Pull requests are welcome and are
brought back into the monorepo from here.

## License

[MIT](LICENSE)
