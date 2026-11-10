package com.waypoint.entity;

import com.waypoint.config.GoogleConnectionIndexConfig;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.repository.GoogleOAuthStateRepository;
import com.waypoint.repository.PlatformGoogleSenderConnectionRepository;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleConnectionModelTest {

    @Test
    void platformSenderIsAFixedIdSingletonAndDoesNotExposeTokensInToString() {
        PlatformGoogleSenderConnection connection = PlatformGoogleSenderConnection.builder()
                .id(PlatformGoogleSenderConnection.ID).refreshTokenCiphertext("CIPHERTEXT-VALUE")
                .refreshTokenIv("IV-VALUE").build();

        assertThat(PlatformGoogleSenderConnection.ID).isEqualTo("platform-gmail-sender");
        assertThat(PlatformGoogleSenderConnection.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class).collection())
                .isEqualTo("platform_google_sender_connection");
        assertThat(connection.toString()).doesNotContain("CIPHERTEXT-VALUE").doesNotContain("IV-VALUE");
        assertThat(org.springframework.data.repository.CrudRepository.class
                .isAssignableFrom(PlatformGoogleSenderConnectionRepository.class)).isTrue();
    }

    @Test
    void connectionUsesItsOwnCollectionAndHoldsOnlyTheRefreshTokenAsCiphertext() {
        assertThat(OrganizationGoogleConnection.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class).collection())
                .isEqualTo("organization_google_connections");
        List<String> fields = Arrays.stream(OrganizationGoogleConnection.class.getDeclaredFields()).map(Field::getName).toList();

        assertThat(fields).contains("organizationId", "status", "googleAccountEmail", "googleSubject",
                "refreshTokenCiphertext", "refreshTokenIv", "encryptionKeyId");
        // Access tokens and plaintext refresh tokens must never be persisted.
        assertThat(fields).noneMatch(name -> name.toLowerCase().contains("accesstoken"))
                .doesNotContain("refreshToken", "accessToken", "clientSecret");
    }

    @Test
    void toStringNeverPrintsTokenCiphertext() {
        OrganizationGoogleConnection connection = OrganizationGoogleConnection.builder().organizationId("org-1")
                .status(GoogleConnectionStatus.CONNECTED).refreshTokenCiphertext("CIPHERTEXT-VALUE")
                .refreshTokenIv("IV-VALUE").build();
        GoogleOAuthState state = GoogleOAuthState.builder().organizationId("org-1")
                .codeVerifierCiphertext("VERIFIER-CIPHERTEXT").codeVerifierIv("VERIFIER-IV").build();

        assertThat(connection.toString()).doesNotContain("CIPHERTEXT-VALUE").doesNotContain("IV-VALUE");
        assertThat(state.toString()).doesNotContain("VERIFIER-CIPHERTEXT").doesNotContain("VERIFIER-IV");
    }

    @Test
    void stateStoresOnlyAHashOfTheStateValue() {
        assertThat(GoogleOAuthState.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class).collection())
                .isEqualTo("google_oauth_states");
        assertThat(Arrays.stream(GoogleOAuthState.class.getDeclaredFields()).map(Field::getName))
                .contains("stateHash", "organizationId", "userId", "expiresAt", "usedAt")
                .doesNotContain("state", "stateValue", "codeVerifier");
    }

    @Test
    void indexesAreOwnedByTheFlagGatedInitializerNotByAnnotations() {
        for (Class<?> type : List.of(OrganizationGoogleConnection.class, GoogleOAuthState.class)) {
            assertThat(type.getAnnotation(CompoundIndex.class)).isNull();
            assertThat(Arrays.stream(type.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Indexed.class))).isEmpty();
        }
    }

    @Test
    void connectionRepositoryOnlyOffersOrganizationAndAccountLookups() {
        List<String> methods = Arrays.stream(OrganizationGoogleConnectionRepository.class.getDeclaredMethods())
                .map(Method::getName).toList();

        assertThat(methods).containsExactlyInAnyOrder("findByOrganizationId", "findByGoogleSubjectAndStatus");
    }

    @Test
    void stateConsumptionIsOneAtomicConditionalUpdate() throws Exception {
        Method consume = GoogleOAuthStateRepository.class.getMethod("consume", String.class, LocalDateTime.class);

        assertThat(consume.getReturnType()).isEqualTo(long.class);
        assertThat(consume.getAnnotation(Query.class).value())
                .contains("'usedAt': null").contains("'expiresAt': { '$gt': ?1 }").contains("'stateHash': ?0");
        assertThat(consume.getAnnotation(Update.class).value()).contains("'usedAt': ?1");
    }

    @Test
    void initializerCreatesTheSecurityIndexesWithTheExpectedShape() {
        MongoTemplate template = mock(MongoTemplate.class);
        IndexOperations connections = mock(IndexOperations.class);
        IndexOperations states = mock(IndexOperations.class);
        when(template.indexOps(OrganizationGoogleConnection.class)).thenReturn(connections);
        when(template.indexOps(GoogleOAuthState.class)).thenReturn(states);

        new GoogleConnectionIndexConfig(template).run(null);

        ArgumentCaptor<IndexDefinition> connectionIndexes = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(connections, org.mockito.Mockito.times(2)).ensureIndex(connectionIndexes.capture());
        ArgumentCaptor<IndexDefinition> stateIndexes = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(states, org.mockito.Mockito.times(2)).ensureIndex(stateIndexes.capture());

        IndexDefinition perOrganization = connectionIndexes.getAllValues().get(0);
        assertThat(perOrganization.getIndexKeys().keySet()).containsExactly("organizationId");
        assertThat(perOrganization.getIndexOptions().get("unique")).isEqualTo(true);

        IndexDefinition perAccount = connectionIndexes.getAllValues().get(1);
        assertThat(perAccount.getIndexKeys().keySet()).containsExactly("googleSubject");
        assertThat(perAccount.getIndexOptions().get("unique")).isEqualTo(true);
        Document partial = (Document) perAccount.getIndexOptions().get("partialFilterExpression");
        assertThat(partial.get("status")).isEqualTo("CONNECTED");

        IndexDefinition hash = stateIndexes.getAllValues().get(0);
        assertThat(hash.getIndexKeys().keySet()).containsExactly("stateHash");
        assertThat(hash.getIndexOptions().get("unique")).isEqualTo(true);

        IndexDefinition expiry = stateIndexes.getAllValues().get(1);
        assertThat(expiry.getIndexKeys().keySet()).containsExactly("expiresAt");
        assertThat(expiry.getIndexOptions().get("expireAfterSeconds")).isEqualTo(0L);
    }
}
