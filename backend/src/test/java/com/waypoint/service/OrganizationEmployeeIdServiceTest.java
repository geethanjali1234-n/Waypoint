package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationEmployeeIdServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationEmployeeIdService service;

    @Test
    void hrAndEmployeeUseTheSameAtomicSequence() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class)))
                .thenReturn(organization(1L), organization(2L), organization(3L));

        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD001");
        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD002");
        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD003");
    }

    @Test
    void formatsRequiredThreeDigitSequenceBoundariesWithoutSeparatorsOrRolePrefixes() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class)))
                .thenReturn(
                        organization(1L), organization(2L), organization(9L),
                        organization(10L), organization(99L), organization(100L));

        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD001");
        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD002");
        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD009");
        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD010");
        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD099");
        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD100");
    }

    @Test
    void organizationsUseTheirOwnCodeAndIndependentSequence() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class)))
                .thenReturn(organization("org-1", "TD", 7L), organization("org-2", "AC", 1L));

        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD007");
        assertThat(service.generateNextLoginId("org-2", UserRole.EMPLOYEE)).isEqualTo("AC001");
    }

    @Test
    void allocationUsesOneMongoFindAndModifyWithIncrement() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class)))
                .thenReturn(organization(17L));

        assertThat(service.generateNextLoginId("org-1", UserRole.HR)).isEqualTo("TD017");

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> optionsCaptor = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(
                any(Query.class), updateCaptor.capture(), optionsCaptor.capture(), eq(Organization.class));

        assertThat(updateCaptor.getValue().getUpdateObject().get("$inc", org.bson.Document.class))
                .containsEntry("nextEmployeeSequence", 1L);
        assertThat(optionsCaptor.getValue().isReturnNew()).isFalse();
        assertThat(optionsCaptor.getValue().isUpsert()).isFalse();
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void sequencePaddingExpandsBeyondThreeDigits() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class)))
                .thenReturn(organization(1000L));

        assertThat(service.generateNextLoginId("org-1", UserRole.EMPLOYEE)).isEqualTo("TD1000");
    }

    @Test
    void companyAdminUsesAdminSuffixWithoutConsumingSequence() {
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization(22L)));

        assertThat(service.generateNextLoginId("org-1", UserRole.COMPANY_ADMIN)).isEqualTo("TDADMIN");

        verify(mongoTemplate, never()).findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Organization.class));
    }

    @Test
    void rejectsRolesThatDoNotUseTheOrganizationEmployeeSequence() {
        assertThatThrownBy(() -> service.generateNextLoginId("org-1", UserRole.JOB_SEEKER))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.generateNextLoginId("org-1", UserRole.EMPLOYER))
                .isInstanceOf(BusinessException.class);
    }

    private Organization organization(long nextSequence) {
        return organization("org-1", "td", nextSequence);
    }

    private Organization organization(String id, String code, long nextSequence) {
        return Organization.builder()
                .id(id)
                .organizationCode(code)
                .nextEmployeeSequence(nextSequence)
                .build();
    }
}
