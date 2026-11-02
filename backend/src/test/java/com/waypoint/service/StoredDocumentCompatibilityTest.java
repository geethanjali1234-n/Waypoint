package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class StoredDocumentCompatibilityTest {
    @Test void readsExistingCompanyPolicyMetadataWithoutBreakingStartup() throws Exception {
        var conversions = MongoCustomConversions.create(adapter -> { });
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setInitialEntitySet(Set.of(StoredDocument.class));
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var saved = new Document("_id", "existing-policy").append("documentType", "COMPANY_POLICY")
                .append("fileName", "handbook.pdf");
        var document = converter.read(StoredDocument.class, saved);
        assertThat(document.getDocumentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(document.getFileName()).isEqualTo("handbook.pdf");
    }
}
