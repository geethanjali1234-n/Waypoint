package com.waypoint.setup;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Index definitions retained for review. Both setup entry points are disabled pending separate approval.
 *
 * <p>Deliberately not a Spring bean, runner, listener, or endpoint. The retained implementation
 * describes a possible future manual setup, but neither public entry point currently executes it.
 *
 * <p>Exception messages thrown from here are written by this class and never include driver
 * text, so callers can print them without exposing connection details.
 */
public class KnowledgeChunkVectorIndexSetup {

    static final String COLLECTION_NAME = "knowledge_chunks";
    static final String INDEX_NAME = "knowledge_chunk_vector_index";
    static final String INDEX_TYPE = "vectorSearch";
    static final String EMBEDDING_PATH = "embedding";
    // Must equal the embedding model's output size (voyage-4-lite at 1024 dimensions).
    static final int NUM_DIMENSIONS = 1024;
    static final String SIMILARITY = "cosine";
    static final List<String> FILTER_PATHS = List.of("organizationId", "accessLevel", "ownerUserId");

    private static final Set<String> VECTOR_FIELD_KEYS =
            Set.of("type", "path", "numDimensions", "similarity", "quantization");
    private static final Set<String> FILTER_FIELD_KEYS = Set.of("type", "path");

    private final MongoDatabase database;

    public KnowledgeChunkVectorIndexSetup(MongoDatabase database) {
        this.database = database;
    }

    /**
     * Index setup is OFF unless an operator who holds the separate approval switches it on explicitly
     * (system property {@code waypoint.knowledge.index-setup.approved=true} or environment variable
     * {@code WAYPOINT_KNOWLEDGE_INDEX_SETUP_APPROVED=true}). Nothing in Waypoint ever starts this class.
     */
    public static boolean approved() {
        // Activation requires a separately reviewed code change, not an ambient environment variable.
        return approvedForTests;
    }

    /** Package-private: lets the offline tests of the retained setup logic run. Never set by application code. */
    static volatile boolean approvedForTests = false;

    private static void requireApproval() {
        if (!approved()) {
            throw new IllegalStateException("Knowledge index setup is disabled pending separate approval.");
        }
    }

    /** Read-only: reports what exists and what --apply would do. Requires the explicit approval switch. */
    public SetupReport check() {
        requireApproval();
        return run(false);
    }

    /** Creates the collection and then the vector index, each only if it is missing. Requires the approval switch. */
    public SetupReport apply() {
        requireApproval();
        return run(true);
    }

    private SetupReport run(boolean apply) {
        String serverVersion = serverVersion();
        boolean collectionCreated = false;
        if (!collectionExists()) {
            if (!apply) {
                return SetupReport.of(Outcome.COLLECTION_MISSING, serverVersion);
            }
            createCollection();
            collectionCreated = true;
        }

        MongoCollection<Document> collection = database.getCollection(COLLECTION_NAME);
        Document existingIndex = findExistingIndex(collection, collectionCreated);
        if (existingIndex != null) {
            List<String> mismatches = definitionMismatches(existingIndex);
            return new SetupReport(
                    mismatches.isEmpty() ? Outcome.INDEX_MATCHES : Outcome.INDEX_MISMATCH,
                    serverVersion,
                    collectionCreated,
                    Objects.toString(existingIndex.get("status"), null),
                    existingIndex.get("queryable") instanceof Boolean queryable ? queryable : null,
                    mismatches);
        }
        if (!apply) {
            return SetupReport.of(Outcome.INDEX_MISSING, serverVersion);
        }
        createIndex(collection, collectionCreated);
        return new SetupReport(Outcome.INDEX_CREATED, serverVersion, collectionCreated, null, null, List.of());
    }

    private String serverVersion() {
        try {
            return Objects.toString(database.runCommand(new Document("buildInfo", 1)).get("version"), "unknown");
        } catch (MongoException exception) {
            throw new SetupFailedException("Could not read the server version (buildInfo). Nothing was changed.",
                    exception);
        }
    }

    private boolean collectionExists() {
        try {
            return database.listCollectionNames().filter(new Document("name", COLLECTION_NAME)).first() != null;
        } catch (MongoException exception) {
            throw new SetupFailedException("Could not list collections. Nothing was changed.", exception);
        }
    }

    private void createCollection() {
        try {
            database.createCollection(COLLECTION_NAME);
        } catch (MongoException exception) {
            throw new SetupFailedException("Could not create collection " + COLLECTION_NAME
                    + ". The vector index was not attempted.", exception);
        }
    }

    private Document findExistingIndex(MongoCollection<Document> collection, boolean collectionCreated) {
        try {
            return collection.listSearchIndexes().name(INDEX_NAME).first();
        } catch (MongoException exception) {
            throw new SetupFailedException("Could not list search indexes on " + COLLECTION_NAME
                    + ". The cluster may not support Atlas Vector Search, or the user lacks the listSearchIndexes"
                    + " privilege. " + collectionState(collectionCreated), exception);
        }
    }

    private void createIndex(MongoCollection<Document> collection, boolean collectionCreated) {
        try {
            // The plural createSearchIndexes is the only driver call that accepts an index type;
            // the singular createSearchIndex(name, definition) always creates a "search" index.
            collection.createSearchIndexes(List.of(
                    new SearchIndexModel(INDEX_NAME, vectorIndexDefinition(), SearchIndexType.vectorSearch())));
        } catch (MongoException exception) {
            throw new SetupFailedException("Atlas did not accept createSearchIndexes for " + INDEX_NAME + ". "
                    + collectionState(collectionCreated), exception);
        }
    }

    private static String collectionState(boolean collectionCreated) {
        return collectionCreated
                ? COLLECTION_NAME + " was created in this run and left empty."
                : COLLECTION_NAME + " was not modified.";
    }

    static Document vectorIndexDefinition() {
        List<Document> fields = new ArrayList<>();
        fields.add(new Document("type", "vector")
                .append("path", EMBEDDING_PATH)
                .append("numDimensions", NUM_DIMENSIONS)
                .append("similarity", SIMILARITY));
        FILTER_PATHS.forEach(path -> fields.add(new Document("type", "filter").append("path", path)));
        return new Document("fields", fields);
    }

    /**
     * Compares a $listSearchIndexes entry with the expected definition. Field order is ignored and
     * numDimensions is compared numerically; any option this setup did not ask for is reported.
     */
    static List<String> definitionMismatches(Document indexInfo) {
        List<String> mismatches = new ArrayList<>();
        Object type = indexInfo.get("type");
        if (!INDEX_TYPE.equals(type)) {
            mismatches.add("index type is " + describe(type) + ", expected '" + INDEX_TYPE + "'");
        }
        if (!(indexInfo.get("latestDefinition") instanceof Document definition)) {
            mismatches.add("latestDefinition was not reported by the server");
            return mismatches;
        }
        definition.keySet().stream()
                .filter(key -> !key.equals("fields"))
                .forEach(key -> mismatches.add("unexpected index option '" + key + "'"));
        if (!(definition.get("fields") instanceof List<?> fields)) {
            mismatches.add("index definition has no fields list");
            return mismatches;
        }

        List<Document> vectorFields = new ArrayList<>();
        Set<Object> filterPaths = new LinkedHashSet<>();
        for (Object entry : fields) {
            if (!(entry instanceof Document field)) {
                mismatches.add("unrecognized field entry " + describe(entry));
                continue;
            }
            Object fieldType = field.get("type");
            if ("vector".equals(fieldType)) {
                vectorFields.add(field);
            } else if ("filter".equals(fieldType)) {
                filterPaths.add(field.get("path"));
                unexpectedKeys(field, FILTER_FIELD_KEYS).forEach(key -> mismatches.add(
                        "filter field " + describe(field.get("path")) + " has unexpected option '" + key + "'"));
            } else {
                mismatches.add("unexpected field type " + describe(fieldType)
                        + " at path " + describe(field.get("path")));
            }
        }

        if (vectorFields.size() != 1) {
            mismatches.add("expected exactly one vector field, found " + vectorFields.size());
        }
        vectorFields.forEach(field -> addVectorFieldMismatches(field, mismatches));
        FILTER_PATHS.stream()
                .filter(path -> !filterPaths.contains(path))
                .forEach(path -> mismatches.add("missing filter field '" + path + "'"));
        filterPaths.stream()
                .filter(path -> !FILTER_PATHS.contains(path))
                .forEach(path -> mismatches.add("unexpected filter field " + describe(path)));
        return mismatches;
    }

    private static void addVectorFieldMismatches(Document field, List<String> mismatches) {
        Object path = field.get("path");
        if (!EMBEDDING_PATH.equals(path)) {
            mismatches.add("vector field path is " + describe(path) + ", expected '" + EMBEDDING_PATH + "'");
        }
        Object dimensions = field.get("numDimensions");
        if (!(dimensions instanceof Number number && number.doubleValue() == NUM_DIMENSIONS)) {
            mismatches.add("numDimensions is " + describe(dimensions) + ", expected " + NUM_DIMENSIONS);
        }
        Object similarity = field.get("similarity");
        if (!SIMILARITY.equals(similarity)) {
            mismatches.add("similarity is " + describe(similarity) + ", expected '" + SIMILARITY + "'");
        }
        Object quantization = field.get("quantization");
        if (quantization != null && !"none".equals(quantization)) {
            mismatches.add("quantization is " + describe(quantization) + ", expected none");
        }
        unexpectedKeys(field, VECTOR_FIELD_KEYS)
                .forEach(key -> mismatches.add("vector field has unexpected option '" + key + "'"));
    }

    private static List<String> unexpectedKeys(Document field, Set<String> allowedKeys) {
        return field.keySet().stream().filter(key -> !allowedKeys.contains(key)).toList();
    }

    private static String describe(Object value) {
        if (value == null) {
            return "missing";
        }
        return value instanceof String text ? "'" + text + "'" : String.valueOf(value);
    }

    public enum Outcome {
        /** Check mode only: the collection does not exist yet. */
        COLLECTION_MISSING,
        /** Check mode only: the collection exists but the index does not. */
        INDEX_MISSING,
        INDEX_MATCHES,
        /** The index exists with a different definition and was left unchanged. */
        INDEX_MISMATCH,
        INDEX_CREATED
    }

    public record SetupReport(Outcome outcome, String serverVersion, boolean collectionCreated,
                              String indexStatus, Boolean indexQueryable, List<String> mismatches) {

        public SetupReport {
            mismatches = List.copyOf(mismatches);
        }

        static SetupReport of(Outcome outcome, String serverVersion) {
            return new SetupReport(outcome, serverVersion, false, null, null, List.of());
        }

        public boolean successful() {
            return outcome != Outcome.INDEX_MISMATCH;
        }

        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            lines.add("MongoDB server version: " + serverVersion);
            switch (outcome) {
                case COLLECTION_MISSING -> {
                    lines.add("Collection " + COLLECTION_NAME + " does not exist.");
                    lines.add("--apply would create it (empty) and then create " + INDEX_NAME + ".");
                    lines.add("Vector Search support cannot be probed read-only until the collection exists;"
                            + " confirm it in the Atlas UI.");
                }
                case INDEX_MISSING -> {
                    lines.add("Collection " + COLLECTION_NAME + " exists.");
                    lines.add("Index " + INDEX_NAME + " does not exist; --apply would create it.");
                }
                case INDEX_MATCHES -> lines.add("Index " + INDEX_NAME + " exists and matches the expected definition"
                        + statusSuffix() + ".");
                case INDEX_MISMATCH -> {
                    lines.add("Index " + INDEX_NAME + " exists but does NOT match the expected definition"
                            + statusSuffix() + ":");
                    mismatches.forEach(mismatch -> lines.add("  - " + mismatch));
                    lines.add("The existing index was left unchanged. Resolve it manually;"
                            + " this tool never updates or drops indexes.");
                }
                case INDEX_CREATED -> {
                    if (collectionCreated) {
                        lines.add("Created empty collection " + COLLECTION_NAME + ".");
                    }
                    lines.add("Submitted " + INDEX_NAME + " (" + INDEX_TYPE + "). Atlas builds it asynchronously;"
                            + " rerun --check until status is READY and queryable is true.");
                }
            }
            return lines;
        }

        private String statusSuffix() {
            return " (status=" + indexStatus + ", queryable=" + indexQueryable + ")";
        }
    }

    /** Failure with a message written by this class; the driver exception is kept only as the cause. */
    public static class SetupFailedException extends RuntimeException {
        SetupFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
