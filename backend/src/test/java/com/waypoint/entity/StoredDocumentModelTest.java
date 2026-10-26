package com.waypoint.entity;

import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class StoredDocumentModelTest {

    @Test
    void usesFinalDocumentsCollectionName() {
        assertThat(StoredDocument.class.getAnnotation(Document.class).collection())
                .isEqualTo("documents");
    }

    @Test
    void storageKeyIsUniquelyIndexed() throws Exception {
        Field field = StoredDocument.class.getDeclaredField("storageKey");
        Indexed indexed = field.getAnnotation(Indexed.class);

        assertThat(indexed).isNotNull();
        assertThat(indexed.unique()).isTrue();
    }

    @Test
    void finalizedDocumentEnumsAreSupported() {
        assertThat(StorageProvider.values()).containsExactly(StorageProvider.R2, StorageProvider.GRIDFS);
        assertThat(DocumentStatus.values()).containsExactly(DocumentStatus.ACTIVE, DocumentStatus.SUPERSEDED);
        assertThat(DocumentType.values()).contains(
                DocumentType.TIMESHEET_SUPPORTING_DOCUMENT,
                DocumentType.REQUESTED_DOCUMENT,
                DocumentType.EMPLOYEE_PROFILE_DOCUMENT,
                DocumentType.TIMESHEET,
                DocumentType.PAYSTUB,
                DocumentType.REPORT,
                DocumentType.RESUME,
                DocumentType.PROFILE_IMAGE,
                DocumentType.ORGANIZATION_LOGO);
    }
}
