package com.waypoint.setup;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetupTool.InvalidInvocationException;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetupTool.Invocation;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetupTool.Mode;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * No real client is ever created: the client factory is a mock, and only non-SRV URIs are used
 * because parsing a mongodb+srv URI performs a DNS lookup.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeChunkVectorIndexSetupToolTest {

    @BeforeAll
    static void operatorApproval() {
        KnowledgeChunkVectorIndexSetup.approvedForTests = true; // the explicit switch is tested elsewhere
    }

    @AfterAll
    static void operatorApprovalCleared() {
        KnowledgeChunkVectorIndexSetup.approvedForTests = false;
    }

    private static final String USER = "svc-vector-setup";
    private static final String PASSWORD = "s3cr3t-Pa55word";
    private static final String HOST = "cluster0-shard-00-00.abcde.mongodb.net";
    private static final String URI = "mongodb://" + USER + ":" + PASSWORD + "@" + HOST + ":27017/?tls=true";
    private static final Map<String, String> ENV = Map.of("MONGODB_URI", URI);

    @Mock Function<ConnectionString, MongoClient> clientFactory;
    @Mock MongoClient client;
    @Mock MongoDatabase database;
    @Mock ListCollectionNamesIterable collectionNames;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    // --- Arguments ------------------------------------------------------------------------------

    @Test
    void defaultsToReadOnlyCheck() {
        assertThat(KnowledgeChunkVectorIndexSetupTool.parseArguments(new String[0]).mode()).isEqualTo(Mode.CHECK);
        assertThat(KnowledgeChunkVectorIndexSetupTool.parseArguments(new String[]{"--check"}).mode())
                .isEqualTo(Mode.CHECK);
    }

    @Test
    void applyRequiresBothConfirmations() {
        Invocation invocation = KnowledgeChunkVectorIndexSetupTool.parseArguments(new String[]{
                "--apply", "--confirm-cluster=" + HOST, "--confirm-database=waypoint"});

        assertThat(invocation).isEqualTo(new Invocation(Mode.APPLY, HOST, "waypoint"));
    }

    @ParameterizedTest
    @MethodSource("unsafeOrAmbiguousArguments")
    void rejectsUnsafeOrAmbiguousArguments(List<String> args) {
        assertThatThrownBy(() -> KnowledgeChunkVectorIndexSetupTool.parseArguments(args.toArray(String[]::new)))
                .isInstanceOf(InvalidInvocationException.class);
    }

    static Stream<List<String>> unsafeOrAmbiguousArguments() {
        return Stream.of(
                List.of("--apply"),
                List.of("--apply", "--confirm-database=waypoint"),
                List.of("--apply", "--confirm-cluster=" + HOST),
                List.of("--apply", "--confirm-cluster=", "--confirm-database=waypoint"),
                List.of("--check", "--apply", "--confirm-cluster=" + HOST, "--confirm-database=waypoint"),
                List.of("--confirm-cluster=" + HOST, "--confirm-database=waypoint"),
                List.of("--apply", "--confirm-cluster=" + HOST, "--confirm-database=waypoint",
                        "--confirm-database=other"),
                List.of("--force"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--uri=" + URI, URI})
    void unrecognizedArgumentsAreNotEchoed(String arg) {
        assertThatThrownBy(() -> KnowledgeChunkVectorIndexSetupTool.parseArguments(new String[]{arg}))
                .isInstanceOf(InvalidInvocationException.class)
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining(USER)
                .hasMessageNotContaining("mongodb://");
    }

    // --- Write confirmation ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {HOST, HOST + ":27017", "CLUSTER0-SHARD-00-00.ABCDE.MONGODB.NET"})
    void writeConfirmationAcceptsTheTargetClusterAndDatabase(String confirmedCluster) {
        Invocation invocation = new Invocation(Mode.APPLY, confirmedCluster, "waypoint");

        assertThatCode(() -> KnowledgeChunkVectorIndexSetupTool.requireWriteConfirmation(
                invocation, List.of(HOST + ":27017"), "waypoint"))
                .doesNotThrowAnyException();
    }

    @Test
    void writeConfirmationRejectsADifferentDatabase() {
        Invocation invocation = new Invocation(Mode.APPLY, HOST, "waypoint_prod");

        assertThatThrownBy(() -> KnowledgeChunkVectorIndexSetupTool.requireWriteConfirmation(
                invocation, List.of(HOST + ":27017"), "waypoint"))
                .isInstanceOf(InvalidInvocationException.class)
                .hasMessageContaining("--confirm-database");
    }

    @Test
    void writeConfirmationRejectsADifferentCluster() {
        Invocation invocation = new Invocation(Mode.APPLY, "cluster1-shard-00-00.zzzzz.mongodb.net", "waypoint");

        assertThatThrownBy(() -> KnowledgeChunkVectorIndexSetupTool.requireWriteConfirmation(
                invocation, List.of(HOST + ":27017"), "waypoint"))
                .isInstanceOf(InvalidInvocationException.class)
                .hasMessageContaining("--confirm-cluster");
    }

    // --- Environment ----------------------------------------------------------------------------

    @Test
    void readsTheSameVariablesAsApplicationYaml() {
        assertThat(KnowledgeChunkVectorIndexSetupTool.resolveUri(Map.of("MONGODB_URI", "a", "MONGODB_URL", "b")))
                .isEqualTo("a");
        assertThat(KnowledgeChunkVectorIndexSetupTool.resolveUri(Map.of("MONGODB_URI", " ", "MONGODB_URL", "b")))
                .isEqualTo("b");
        assertThat(KnowledgeChunkVectorIndexSetupTool.resolveDatabase(Map.of())).isEqualTo("waypoint");
        assertThat(KnowledgeChunkVectorIndexSetupTool.resolveDatabase(Map.of("MONGODB_DATABASE", "waypoint_dev")))
                .isEqualTo("waypoint_dev");
        assertThatThrownBy(() -> KnowledgeChunkVectorIndexSetupTool.resolveUri(Map.of()))
                .isInstanceOf(InvalidInvocationException.class);
    }

    // --- End-to-end with a mocked client --------------------------------------------------------

    @Test
    void applyWithoutConfirmationNeverConnects() {
        int exitCode = run(ENV, "--apply");

        assertThat(exitCode).isEqualTo(1);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void applyWithTheWrongClusterNeverConnects() {
        int exitCode = run(ENV, "--apply", "--confirm-cluster=other.mongodb.net", "--confirm-database=waypoint");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err()).contains("--confirm-cluster does not match").contains("Nothing was changed");
        verifyNoInteractions(clientFactory);
    }

    @Test
    void checkPrintsOnlyTheHostAndDatabaseAndClosesTheClient() {
        givenConnectedDatabase();
        when(database.runCommand(new Document("buildInfo", 1))).thenReturn(new Document("version", "8.0.4"));
        when(database.listCollectionNames()).thenReturn(collectionNames);
        when(collectionNames.filter(new Document("name", "knowledge_chunks"))).thenReturn(collectionNames);
        when(collectionNames.first()).thenReturn(null);

        int exitCode = run(ENV, "--check");

        assertThat(exitCode).isZero();
        assertThat(out()).contains("Target cluster host(s): " + HOST + ":27017")
                .contains("Target database: waypoint")
                .contains("CHECK (read-only)")
                .contains("Collection knowledge_chunks does not exist")
                .contains("nothing was changed");
        assertNoSecrets(out() + err());
        verify(database, never()).createCollection(anyString());
        verify(client).close();
    }

    @Test
    void authenticationFailureIsSanitized() {
        givenConnectedDatabase();
        MongoCredential credential = MongoCredential.createScramSha256Credential(USER, "admin", PASSWORD.toCharArray());
        when(database.runCommand(new Document("buildInfo", 1))).thenThrow(new MongoSecurityException(credential,
                "Exception authenticating " + credential + " using " + URI));

        int exitCode = run(ENV, "--check");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err()).contains("Authentication failed");
        assertNoSecrets(out() + err());
        verify(client).close();
    }

    @Test
    void timeoutIsSanitized() {
        givenConnectedDatabase();
        when(database.runCommand(new Document("buildInfo", 1)))
                .thenThrow(new MongoTimeoutException("Timed out while connecting with " + URI));

        int exitCode = run(ENV, "--check");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err()).contains("Timed out reaching the cluster");
        assertNoSecrets(out() + err());
    }

    @Test
    void invalidConnectionStringIsSanitizedAndNeverConnects() {
        int exitCode = run(Map.of("MONGODB_URI", "mongodb://" + USER + ":" + PASSWORD + "@"), "--check");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err()).contains("not a valid MongoDB connection string");
        assertNoSecrets(out() + err());
        verifyNoInteractions(clientFactory);
    }

    @Test
    void serverErrorMessagesAreRedacted() {
        BsonDocument response = BsonDocument.parse("{\"ok\": 0, \"code\": 8000, \"codeName\": \"AtlasError\","
                + " \"errmsg\": \"rejected for " + USER + " with password " + PASSWORD + " via " + URI + "\"}");
        MongoCommandException failure = new MongoCommandException(response, new ServerAddress(HOST));

        String description = KnowledgeChunkVectorIndexSetupTool.describeFailure(failure, URI);

        assertThat(description).contains("code 8000 AtlasError");
        assertNoSecrets(description);
    }

    @Test
    void unexpectedFailureDetailsAreSuppressed() {
        String description = KnowledgeChunkVectorIndexSetupTool.describeFailure(
                new IllegalStateException("state should be: open " + URI), URI);

        assertThat(description).isEqualTo(
                "IllegalStateException (details suppressed so connection details are not exposed).");
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void givenConnectedDatabase() {
        when(clientFactory.apply(any())).thenReturn(client);
        when(client.getDatabase("waypoint")).thenReturn(database);
    }

    private int run(Map<String, String> env, String... args) {
        return KnowledgeChunkVectorIndexSetupTool.run(args, env, clientFactory,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static void assertNoSecrets(String text) {
        assertThat(text).doesNotContain(PASSWORD).doesNotContain(USER).doesNotContain("mongodb://");
    }
}
