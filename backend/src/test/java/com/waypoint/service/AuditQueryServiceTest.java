package com.waypoint.service;

import com.waypoint.dto.myhr.AuditHistoryResponse;
import com.waypoint.entity.AuditLog;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import com.waypoint.service.AuditQueryService.AuditHistoryQuery;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock MyHrAuthenticatedUserService authenticatedUserService;

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(mongoTemplate, authenticatedUserService);
    }

    private void actor(UserRole role) {
        when(authenticatedUserService.require(eq("admin-1"), eq(Set.of(UserRole.COMPANY_ADMIN)), eq(false)))
                .thenReturn(new AuthenticatedMyHrUser("admin-1", "TDADMIN", "admin@tardus.test", role, "org-1",
                        AccountStatus.ACTIVE, false));
    }

    private AuditLog logRow(String id) {
        return AuditLog.builder()
                .id(id)
                .organizationId("org-1")
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .performedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void rejectsNonCompanyAdminCallers() {
        when(authenticatedUserService.require(eq("hr-1"), eq(Set.of(UserRole.COMPANY_ADMIN)), eq(false)))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        assertThatThrownBy(() -> service.getHistory("hr-1", AuditHistoryQuery.builder().build()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void defaultsPageAndSizeWhenNotProvided() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        AuditHistoryResponse response = service.getHistory("admin-1", AuditHistoryQuery.builder().build());

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(25);
        assertThat(response.totalElements()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(0);
    }

    @Test
    void clampsNegativePageToZeroAndCapsSizeAtMax() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(500L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        AuditHistoryResponse response = service.getHistory("admin-1",
                AuditHistoryQuery.builder().page(-5).size(1000).build());

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.totalPages()).isEqualTo(5);
    }

    @Test
    void treatsZeroOrNegativeSizeAsDefault() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        AuditHistoryResponse response = service.getHistory("admin-1",
                AuditHistoryQuery.builder().size(0).build());

        assertThat(response.size()).isEqualTo(25);
    }

    @Test
    void mapsRowsToResponsesWithLabelAndCategoryFromCatalog() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of(logRow("log-1")));

        AuditHistoryResponse response = service.getHistory("admin-1", AuditHistoryQuery.builder().build());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo("log-1");
        assertThat(response.items().get(0).actionLabel()).isEqualTo("Account status changed");
        assertThat(response.items().get(0).category()).isEqualTo(AuditEventCategory.ACCOUNT_MANAGEMENT);
    }

    @Test
    void alwaysScopesQueryToTheCallerOrganization() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        service.getHistory("admin-1", AuditHistoryQuery.builder().build());

        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AuditLog.class));
        assertThat(andClauses(captor.getValue())).anyMatch(d -> "org-1".equals(d.get("organizationId")));
    }

    @Test
    void appliesActionTypeAndEntityFilters() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        service.getHistory("admin-1", AuditHistoryQuery.builder()
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .entityType("USER")
                .entityId("emp-1")
                .actorUserId("hr-1")
                .build());

        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AuditLog.class));
        List<Document> clauses = andClauses(captor.getValue());
        assertThat(clauses).anyMatch(d -> AuditActionType.ACCOUNT_STATUS_CHANGED.equals(d.get("actionType")));
        assertThat(clauses).anyMatch(d -> "USER".equals(d.get("entityType")));
        assertThat(clauses).anyMatch(d -> "emp-1".equals(d.get("entityId")));
        assertThat(clauses).anyMatch(d -> "hr-1".equals(d.get("performedByUserId")));
    }

    @Test
    void appliesDateRangeFilterInclusiveOfTheToDate() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        service.getHistory("admin-1", AuditHistoryQuery.builder()
                .from(LocalDate.of(2026, 1, 1))
                .to(LocalDate.of(2026, 1, 31))
                .build());

        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AuditLog.class));
        List<Document> clauses = andClauses(captor.getValue());
        Document dateClause = clauses.stream().filter(d -> d.containsKey("performedAt")).findFirst().orElseThrow();
        Document range = (Document) dateClause.get("performedAt");
        assertThat(range.get("$gte")).isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay());
        assertThat(range.get("$lt")).isEqualTo(LocalDate.of(2026, 2, 1).atStartOfDay());
    }

    @Test
    void blankSearchAndTrimmedFiltersAreIgnoredOrTrimmed() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        service.getHistory("admin-1", AuditHistoryQuery.builder()
                .search("   ")
                .actorUserId("  hr-1  ")
                .build());

        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AuditLog.class));
        List<Document> clauses = andClauses(captor.getValue());
        assertThat(clauses).noneMatch(d -> d.containsKey("$or"));
        assertThat(clauses).anyMatch(d -> "hr-1".equals(d.get("performedByUserId")));
    }

    @Test
    void wrapsFreeTextSearchAcrossMultipleFieldsWithOrCriteria() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        service.getHistory("admin-1", AuditHistoryQuery.builder().search("jane").build());

        org.mockito.Mockito.verify(mongoTemplate).count(captor.capture(), eq(AuditLog.class));
        List<Document> clauses = andClauses(captor.getValue());
        assertThat(clauses).anyMatch(d -> d.containsKey("$or"));
    }

    @SuppressWarnings("unchecked")
    private List<Document> andClauses(Query query) {
        return (List<Document>) query.getQueryObject().get("$and");
    }

    @Test
    void returnsFullActionCatalogAndCategoryNamesOnEveryPage() {
        actor(UserRole.COMPANY_ADMIN);
        when(mongoTemplate.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());

        AuditHistoryResponse response = service.getHistory("admin-1", AuditHistoryQuery.builder().build());

        assertThat(response.actionCatalog()).hasSize(AuditActionType.values().length);
        assertThat(response.categories()).containsExactlyInAnyOrder(
                java.util.Arrays.stream(AuditEventCategory.values()).map(Enum::name).toArray(String[]::new));
    }
}
