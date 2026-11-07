import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * Minimal JDBC query tool for the QA rig. The embedded Postgres bundle ships no psql, so this
 * single-file program is the SQL fallback for {@code scripts/qa/release_checks.py} and for manual
 * checks.
 *
 * <p>Run it with the Java source launcher and the pgjdbc jar from the Gradle cache:
 *
 * <pre>
 *   java -cp "$QA_PGJDBC_JAR" scripts/qa/rig/PgQuery.java "select count(*) from bill"
 * </pre>
 *
 * <p>The connection comes from the environment only, so no credential is ever written into a
 * script: {@code QA_PG_HOST} (default localhost), {@code QA_PG_PORT} (required), {@code QA_PG_DB}
 * (default pos_db), {@code QA_PG_USER} and {@code QA_PG_PASSWORD} (both required).
 *
 * <p>Output is tab-separated: one header line, then one line per row. NULL prints as an empty
 * field. A statement that returns no result set prints {@code updated <n>}.
 */
public class PgQuery {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java -cp <pgjdbc.jar> PgQuery.java \"<sql>\"");
            System.exit(2);
        }
        String port = requireEnv("QA_PG_PORT");
        String user = requireEnv("QA_PG_USER");
        String password = requireEnv("QA_PG_PASSWORD");
        String host = envOr("QA_PG_HOST", "localhost");
        String db = envOr("QA_PG_DB", "pos_db");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            if (!s.execute(args[0])) {
                System.out.println("updated " + s.getUpdateCount());
                return;
            }
            printResult(s.getResultSet());
        }
    }

    private static void printResult(ResultSet r) throws Exception {
        ResultSetMetaData m = r.getMetaData();
        int n = m.getColumnCount();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            header.append(i > 1 ? "\t" : "").append(m.getColumnLabel(i));
        }
        System.out.println(header);
        while (r.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                String v = r.getString(i);
                row.append(i > 1 ? "\t" : "").append(v == null ? "" : v.replace('\t', ' ').replace('\n', ' '));
            }
            System.out.println(row);
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            System.err.println("missing environment variable " + name);
            System.exit(2);
        }
        return v;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }
}
