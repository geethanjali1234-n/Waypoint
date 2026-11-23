package com.waypoint.entity;

import com.waypoint.setup.KnowledgeChunkVectorIndexSetup;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePersistenceNoAutomaticIndexTest {
    @Test void newPersistenceTypesDoNotDeclareAutomaticallyCreatedIndexes() {
        for (Class<?> type : new Class<?>[] {KnowledgeChunk.class, KnowledgeDocument.class, ExternalProcessingApproval.class}) {
            assertThat(type.getAnnotation(CompoundIndexes.class)).isNull();
            assertThat(type.getAnnotation(CompoundIndex.class)).isNull();
            for (var field : type.getDeclaredFields()) {
                assertThat(field.getAnnotation(Indexed.class)).as(type.getSimpleName() + "." + field.getName()).isNull();
            }
        }
    }

    @Test void vectorIndexSetupIsNotASpringComponent() {
        assertThat(KnowledgeChunkVectorIndexSetup.class.getAnnotation(Component.class)).isNull();
    }
}
