# QueryDesk JDBC driver

In DataGrip, use the [plugin](../README.md), which ships this driver and keeps you signed in.

A JDBC driver that opens no connection to the database. Each statement goes to QueryDesk's
`POST /api/v1/querydesk/statements` over HTTPS and runs there as the signed-in member, with
their access, reviews, data protection and the audit log applied. One statement's rows stream
back as QueryDesk reads them, and closing the result stops the query. No dependencies, Java 17.

```bash
jdbc-driver/test.sh
```

builds `build/querydesk-jdbc.jar` and runs `DriverTest` against a local stand-in for the API.

## DataGrip

1. Database → `+` → Driver. Add `build/querydesk-jdbc.jar` under Driver Files, class
   `tools.devhub.querydesk.jdbc.QueryDeskDriver`, and set Dialect to PostgreSQL.
2. New data source from that driver. URL `jdbc:querydesk://<host>/<credential id>`
   (`?ssl=false` for a local server). Leave Password empty: connecting opens the browser to sign
   in to QueryDesk. Tokens stay in the tool's memory, so it signs in once per run. A token in
   Password is used as it is.
3. Leave the transaction mode on Auto: the driver can't hold a transaction open yet.

Run SQL against a real server without DataGrip:

```bash
QUERYDESK_TOKEN=... java -cp jdbc-driver/build/querydesk-jdbc.jar:jdbc-driver/build/test-classes \
  tools.devhub.querydesk.jdbc.Smoke 'jdbc:querydesk://localhost:6600/crd_...?ssl=false' 'SELECT 1'
```

`&trace=<file>` on the URL appends every JDBC call a tool makes, and what it got back, to that file.

## Limitations

- Transactions: not supported yet.
- Types come from the values QueryDesk returns, not from the database. A 16-byte binary value reads
  as a uuid, and an empty column has no type.
- Every introspection query DataGrip runs is recorded in the audit log as a Query.
- Prepared statement parameters are substituted into the SQL as literals on the client.
