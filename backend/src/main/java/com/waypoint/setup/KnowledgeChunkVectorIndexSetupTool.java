package com.waypoint.setup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetup.SetupFailedException;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetup.SetupReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual entry point retained for review. Execution is disabled and Spring never starts it.
 *
 * <pre>
 *   --check   (default) read-only report of the collection and vector index
 *   --apply --confirm-cluster=&lt;cluster host&gt; --confirm-database=&lt;database&gt;
 *             create knowledge_chunks and/or the vector index if missing
 * </pre>
 *
 * <p>Reads MONGODB_URI (falling back to MONGODB_URL) and MONGODB_DATABASE (default waypoint), the
 * same variables application.yaml uses. The app has no environment/profile setting, so the target
 * environment is confirmed by its cluster host. Output shows only the cluster host(s) and database
 * name; the connection string, credentials, and raw driver messages are never printed.
 */
public final class KnowledgeChunkVectorIndexSetupTool {

    static final String DEFAULT_DATABASE = "waypoint";

    private static final String CHECK = "--check";
    private static final String APPLY = "--apply";
    private static final String CONFIRM_CLUSTER = "--confirm-cluster=";
    private static final String CONFIRM_DATABASE = "--confirm-database=";
    private static final String USAGE =
            "Usage: --check | --apply --confirm-cluster=<cluster host> --confirm-database=<database>";
    private static final String REDACTED = "<redacted>";

    private static final Pattern CONNECTION_STRING =
            Pattern.compile("mongodb(\\+srv)?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_INFO =
            Pattern.compile("^mongodb(?:\\+srv)?://([^@/?]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_WITH_PORT = Pattern.compile("^(.+):\\d+$");

    enum Mode { CHECK, APPLY }

    record Invocation(Mode mode, String confirmCluster, String confirmDatabase) {
    }

    /** Argument or configuration problem; its message is always safe to print. */
    static class InvalidInvocationException extends RuntimeException {
        InvalidInvocationException(String message) {
            super(message);
        }
    }

    private KnowledgeChunkVectorIndexSetupTool() {
    }

    public static void main(String[] args) {
        silenceLibraryLogging();
        System.exit(run(args, System.getenv(), MongoClients::create, System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, Function<ConnectionString, MongoClient> clientFactory,
                   PrintStream out, PrintStream err) {
        if (!KnowledgeChunkVectorIndexSetup.approved()) {
            err.println("Knowledge index setup is disabled pending separate approval.");
            return 1;
        }
        String uri = null;
        try {
            Invocation invocation = parseArguments(args);
            uri = resolveUri(env);
            String database = resolveDatabase(env);
            ConnectionString connectionString = parseConnectionString(uri);
            List<String> hosts = connectionString.getHosts();

            out.println("Target cluster host(s): " + String.join(", ", hosts));
            out.println("Target database: " + database);
            out.println("Mode: " + (invocation.mode() == Mode.APPLY
                    ? "APPLY (creates missing resources only)"
                    : "CHECK (read-only)"));
            if (invocation.mode() == Mode.APPLY) {
                requireWriteConfirmation(invocation, hosts, database);
            }

            try (MongoClient client = clientFactory.apply(connectionString)) {
                KnowledgeChunkVectorIndexSetup setup = new KnowledgeChunkVectorIndexSetup(client.getDatabase(database));
                SetupReport report = invocation.mode() == Mode.APPLY ? setup.apply() : setup.check();
                report.describe().forEach(out::println);
                if (invocation.mode() == Mode.CHECK) {
                    out.println("Read-only check: nothing was changed.");
                }
                return report.successful() ? 0 : 1;
            }
        } catch (Exception failure) {
            err.println("Setup failed: " + describeFailure(failure, uri));
            return 1;
        }
    }

    static Invocation parseArguments(String[] args) {
        Mode mode = null;
        String confirmCluster = null;
        String confirmDatabase = null;
        for (String arg : args) {
            if ((arg.equals(CHECK) || arg.equals(APPLY)) && mode == null) {
                mode = arg.equals(APPLY) ? Mode.APPLY : Mode.CHECK;
            } else if (arg.startsWith(CONFIRM_CLUSTER) && confirmCluster == null) {
                confirmCluster = arg.substring(CONFIRM_CLUSTER.length());
            } else if (arg.startsWith(CONFIRM_DATABASE) && confirmDatabase == null) {
                confirmDatabase = arg.substring(CONFIRM_DATABASE.length());
            } else {
                throw new InvalidInvocationException(
                        "Unrecognized or repeated argument " + safeArgumentName(arg) + ". " + USAGE);
            }
        }

        if (mode == null) {
            mode = Mode.CHECK;
        }
        if (mode == Mode.CHECK && (confirmCluster != null || confirmDatabase != null)) {
            throw new InvalidInvocationException(
                    "--confirm-cluster and --confirm-database are only valid with --apply. " + USAGE);
        }
        if (mode == Mode.APPLY && (isBlank(confirmCluster) || isBlank(confirmDatabase))) {
            throw new InvalidInvocationException("--apply requires both --confirm-cluster=<cluster host> and"
                    + " --confirm-database=<database>. Run --check first to see the target.");
        }
        return new Invocation(mode, confirmCluster, confirmDatabase);
    }

    static void requireWriteConfirmation(Invocation invocation, List<String> hosts, String database) {
        if (!database.equals(invocation.confirmDatabase())) {
            throw new InvalidInvocationException("--confirm-database does not match the target database '"
                    + database + "'. Nothing was changed.");
        }
        boolean clusterConfirmed = hosts.stream().anyMatch(host -> sameHost(host, invocation.confirmCluster()));
        if (!clusterConfirmed) {
            throw new InvalidInvocationException(
                    "--confirm-cluster does not match the target cluster host(s). Nothing was changed.");
        }
    }

    static String resolveUri(Map<String, String> env) {
        for (String name : List.of("MONGODB_URI", "MONGODB_URL")) {
            String value = env.get(name);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        throw new InvalidInvocationException("Set MONGODB_URI (or MONGODB_URL) to the target cluster's"
                + " connection string.");
    }

    static String resolveDatabase(Map<String, String> env) {
        String value = env.get("MONGODB_DATABASE");
        return isBlank(value) ? DEFAULT_DATABASE : value.trim();
    }

    /** Never returns driver exception messages, which can contain hosts, user names, or the URI. */
    static String describeFailure(Throwable failure, String uri) {
        if (failure instanceof InvalidInvocationException) {
            return failure.getMessage();
        }
        if (failure instanceof SetupFailedException) {
            return failure.getCause() == null
                    ? failure.getMessage()
                    : failure.getMessage() + " Cause: " + describeFailure(failure.getCause(), uri);
        }
        if (failure instanceof MongoCommandException command) {
            return "Server rejected the command (code " + command.getErrorCode() + " "
                    + command.getErrorCodeName() + "): " + redact(command.getErrorMessage(), uri);
        }
        if (failure instanceof MongoSecurityException) {
            return "Authentication failed. Check the database user, password, and authSource.";
        }
        if (failure instanceof MongoTimeoutException) {
            return "Timed out reaching the cluster. Check the Atlas IP access list, the network,"
                    + " and that the cluster is running.";
        }
        if (failure instanceof MongoConfigurationException) {
            return "The connection configuration was rejected (for example, an SRV/DNS lookup failed).";
        }
        if (failure instanceof MongoSocketException) {
            return "A network error occurred while talking to the cluster.";
        }
        return failure.getClass().getSimpleName() + " (details suppressed so connection details are not exposed).";
    }

    /** Server error text should not contain secrets, but strip anything that looks like one anyway. */
    static String redact(String text, String uri) {
        if (text == null) {
            return "";
        }
        String redacted = text;
        if (uri != null) {
            redacted = redacted.replace(uri, REDACTED);
            for (String secret : credentialParts(uri)) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        return CONNECTION_STRING.matcher(redacted).replaceAll(REDACTED);
    }

    private static List<String> credentialParts(String uri) {
        Matcher matcher = USER_INFO.matcher(uri);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : matcher.group(1).split(":", 2)) {
            if (part.isEmpty()) {
                continue;
            }
            parts.add(part);
            try {
                parts.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Malformed percent-encoding: the raw form above is still redacted.
            }
        }
        return parts;
    }

    private static ConnectionString parseConnectionString(String uri) {
        try {
            return new ConnectionString(uri);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInvocationException("MONGODB_URI/MONGODB_URL is not a valid MongoDB connection string.");
        }
    }

    private static boolean sameHost(String host, String confirmed) {
        Matcher matcher = HOST_WITH_PORT.matcher(host);
        String hostWithoutPort = matcher.matches() ? matcher.group(1) : host;
        return host.equalsIgnoreCase(confirmed) || hostWithoutPort.equalsIgnoreCase(confirmed);
    }

    /** Echo only an option's name; a pasted value could be a connection string. */
    private static String safeArgumentName(String arg) {
        return arg.startsWith("--") ? "'" + arg.split("=", 2)[0] + "'" : "(non-option value hidden)";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The app ships no logback config, so a standalone run would log the driver at DEBUG. */
    private static void silenceLibraryLogging() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        }
    }
}
