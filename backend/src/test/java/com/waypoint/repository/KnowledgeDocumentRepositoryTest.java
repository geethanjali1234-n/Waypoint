package com.waypoint.repository;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.enums.KnowledgeDocumentStatus;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.util.json.ParameterBindingDocumentCodec;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declaration and query-structure tests only; no database connection is made. Derived queries are
 * parsed with Spring Data's own PartTree, and the explicit @Query is bound with the codec Spring
 * Data uses for string queries, so the filters checked here are the ones that would be sent.
 */
class KnowledgeDocumentRepositoryTest {

    private static final Method EXISTS_WITH_SOURCE_DOCUMENT = method(
            "existsWithSourceDocument", String.class, KnowledgeDocumentStatus.class, String.class);
    private static final MappingMongoConverter CONVERTER = converter();

    // --- Declarations ---------------------------------------------------------------------------

    @Test
    void extendsMongoRepositoryOfKnowledgeDocumentWithStringIds() {
        ParameterizedType repository = (ParameterizedType) KnowledgeDocumentRepository.class.getGenericInterfaces()[0];

        assertThat(repository.getRawType()).isEqualTo(MongoRepository.class);
        assertThat(repository.getActualTypeArguments()).containsExactly(KnowledgeDocument.class, String.class);
    }

    @ParameterizedTest
    @MethodSource("declaredSignatures")
    void declaresEachMethodWithItsSignature(String name, Class<?> returnType, Class<?>[] parameterTypes) {
        assertThat(method(name, parameterTypes).getReturnType()).isEqualTo(returnType);
    }

    static Stream<Arguments> declaredSignatures() {
        Class<?>[] organizationStatusValue = {String.class, KnowledgeDocumentStatus.class, String.class};
        return Stream.of(
                Arguments.of("findByIdAndOrganizationId", Optional.class, new Class<?>[]{String.class, String.class}),
                Arguments.of("findByOrganizationIdOrderByCreatedAtDesc", List.class, new Class<?>[]{String.class}),
                Arguments.of("findByOrganizationIdAndStatus", List.class,
                        new Class<?>[]{String.class, KnowledgeDocumentStatus.class}),
                Arguments.of("existsWithSourceDocument", boolean.class, organizationStatusValue),
                Arguments.of("existsByOrganizationIdAndStatusAndActiveVersionContentSha256", boolean.class,
                        organizationStatusValue),
                Arguments.of("existsByOrganizationIdAndStatusAndPendingVersionContentSha256", boolean.class,
                        organizationStatusValue));
    }

    @Test
    void declaresOnlyTheseMethodsAndNoDeletes() {
        assertThat(declaredMethods().map(Method::getName)).containsExactlyInAnyOrder(
                "findByIdAndOrganizationId",
                "findByOrganizationIdOrderByCreatedAtDesc",
                "findByOrganizationIdAndStatus",
                "existsWithSourceDocument",
                "existsByOrganizationIdAndStatusAndActiveVersionContentSha256",
                "existsByOrganizationIdAndStatusAndPendingVersionContentSha256");
        assertThat(declaredMethods()).noneMatch(method -> method.getName().startsWith("delete")
                || method.getName().startsWith("remove"));
    }

    @Test
    void everyDeclaredMethodFiltersByTheOrganizationArgument() {
        declaredMethods().forEach(method -> {
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                assertThat(properties(method.getName())).as(method.getName()).contains("organizationId");
            } else {
                // Bind each argument to a marker to see which one lands on organizationId.
                Object[] markers = IntStream.range(0, method.getParameterCount()).mapToObj(index -> "arg" + index).toArray();
                Document filter = new ParameterBindingDocumentCodec().decode(query.value(), markers);
                assertThat(filter.get("organizationId")).as(method.getName()).isEqualTo("arg0");
                assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
            }
        });
    }

    // --- Derived queries ------------------------------------------------------------------------

    @Test
    void derivedQueriesResolveAgainstTheEntityAndUseExactEqualityOnly() {
        declaredMethods().filter(method -> !method.isAnnotationPresent(Query.class)).forEach(method -> {
            PartTree tree = new PartTree(method.getName(), KnowledgeDocument.class);
            assertThat(tree.getParts()).as(method.getName())
                    .allSatisfy(part -> assertThat(part.getType()).isEqualTo(Part.Type.SIMPLE_PROPERTY));
        });
    }

    @Test
    void entryLookupRequiresBothIdAndOrganization() {
        assertThat(properties("findByIdAndOrganizationId")).containsExactly("id", "organizationId");
    }

    @Test
    void organizationListingIsNewestFirst() {
        PartTree tree = new PartTree("findByOrganizationIdOrderByCreatedAtDesc", KnowledgeDocument.class);

        assertThat(properties(tree)).containsExactly("organizationId");
        Sort.Order order = tree.getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void contentHashLookupsReachIntoTheEmbeddedVersions() {
        for (String version : List.of("Active", "Pending")) {
            PartTree tree = new PartTree(
                    "existsByOrganizationIdAndStatusAnd" + version + "VersionContentSha256", KnowledgeDocument.class);
            assertThat(tree.isExistsProjection()).isTrue();
            assertThat(properties(tree)).containsExactly(
                    "organizationId", "status", version.toLowerCase() + "Version.contentSha256");
        }
    }

    // --- Explicit source-document query ---------------------------------------------------------

    @Test
    void sourceDocumentLookupIsAnExplicitExistsQuery() {
        Query query = EXISTS_WITH_SOURCE_DOCUMENT.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.exists()).isTrue();
        assertThat(query.count()).isFalse();
        assertThat(query.delete()).isFalse();
    }

    @Test
    void sourceDocumentLookupBindsToPlainOrganizationScopedEquality() {
        Document filter = boundSourceDocumentFilter("org-1", KnowledgeDocumentStatus.LIVE, "doc123");

        assertThat(filter.keySet()).containsExactly("organizationId", "status", "sourceDocumentIds");
        assertThat(filter).isEqualTo(new Document("organizationId", "org-1")
                .append("status", "LIVE")
                .append("sourceDocumentIds", "doc123"));
        // Plain strings only: no operator document, regex or pattern can widen the match.
        assertThat(filter.values()).allSatisfy(value -> assertThat(value).isExactlyInstanceOf(String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc1234", "doc12", "xdoc123", "doc1230", "DOC123", "doc123 ", " doc123"})
    void sourceDocumentIdNeverMatchesADifferentId(String storedId) {
        Document filter = boundSourceDocumentFilter("org-1", KnowledgeDocumentStatus.LIVE, "doc123");

        assertThat(matches(filter, entry("org-1", "LIVE", List.of(storedId)))).isFalse();
        assertThat(matches(filter, entry("org-1", "LIVE", List.of("doc999", storedId)))).isFalse();
    }

    @Test
    void sourceDocumentIdMatchesAnyEqualElementOnlyWithinTheOrganizationAndStatus() {
        Document filter = boundSourceDocumentFilter("org-1", KnowledgeDocumentStatus.LIVE, "doc123");

        assertThat(matches(filter, entry("org-1", "LIVE", List.of("doc123")))).isTrue();
        assertThat(matches(filter, entry("org-1", "LIVE", List.of("doc1234", "doc123")))).isTrue();
        assertThat(matches(filter, entry("org-2", "LIVE", List.of("doc123")))).isFalse();
        assertThat(matches(filter, entry("org-1", "REVOKED", List.of("doc123")))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ '$ne': null }", "{ \"$regex\": \"doc\" }", "doc.*", "/doc123/", "^doc",
            "doc123', 'status': 'REVOKED"})
    void sourceDocumentIdIsBoundAsALiteralValueNotAsQuerySyntax(String hostileId) {
        Document filter = boundSourceDocumentFilter("org-1", KnowledgeDocumentStatus.LIVE, hostileId);

        assertThat(filter.keySet()).containsExactly("organizationId", "status", "sourceDocumentIds");
        assertThat(filter.get("organizationId")).isEqualTo("org-1");
        assertThat(filter.get("status")).isEqualTo("LIVE");
        assertThat(filter.get("sourceDocumentIds")).isExactlyInstanceOf(String.class).isEqualTo(hostileId);
    }

    // --- Helpers --------------------------------------------------------------------------------

    /**
     * Binds the @Query the way Spring Data does for string queries: arguments are first converted by
     * the mapping converter, which turns the enum into its name, then bound by ParameterBindingDocumentCodec.
     */
    private static Document boundSourceDocumentFilter(
            String organizationId, KnowledgeDocumentStatus status, String sourceDocumentId) {
        Object[] arguments = Stream.of(organizationId, status, sourceDocumentId)
                .map(CONVERTER::convertToMongoType)
                .toArray();
        return new ParameterBindingDocumentCodec()
                .decode(EXISTS_WITH_SOURCE_DOCUMENT.getAnnotation(Query.class).value(), arguments);
    }

    /**
     * MongoDB's documented semantics for a filter of plain field/value pairs: every pair must hold,
     * and a pair holds when the field equals the value or, for an array field, when any element
     * equals it. Operators are rejected so this cannot paper over a filter that uses them.
     */
    private static boolean matches(Document filter, Document entry) {
        for (Map.Entry<String, Object> condition : filter.entrySet()) {
            assertThat(condition.getKey()).doesNotStartWith("$");
            assertThat(condition.getValue()).isExactlyInstanceOf(String.class);
            Object actual = entry.get(condition.getKey());
            boolean holds = actual instanceof List<?> elements
                    ? elements.contains(condition.getValue())
                    : Objects.equals(actual, condition.getValue());
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    private static Document entry(String organizationId, String status, List<String> sourceDocumentIds) {
        return new Document("organizationId", organizationId)
                .append("status", status)
                .append("sourceDocumentIds", sourceDocumentIds);
    }

    private static List<String> properties(String methodName) {
        return properties(new PartTree(methodName, KnowledgeDocument.class));
    }

    private static List<String> properties(PartTree tree) {
        return tree.getParts().stream().map(part -> part.getProperty().toDotPath()).toList();
    }

    private static Stream<Method> declaredMethods() {
        return Arrays.stream(KnowledgeDocumentRepository.class.getDeclaredMethods()).filter(method -> !method.isSynthetic());
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return KnowledgeDocumentRepository.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("KnowledgeDocumentRepository." + name + " is not declared", missing);
        }
    }

    private static MappingMongoConverter converter() {
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, new MongoMappingContext());
        converter.afterPropertiesSet();
        return converter;
    }
}
