package com.waypoint.service;

import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.DocumentRequestReminder;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentRequestIndexInitializerTest {

    @Test
    void createsTheReminderIndexesAlongsideTheExistingWorklistIndexes() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        IndexOperations requestIndexes = mock(IndexOperations.class);
        IndexOperations reminderIndexes = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(DocumentRequest.class)).thenReturn(requestIndexes);
        when(mongoTemplate.indexOps(DocumentRequestReminder.class)).thenReturn(reminderIndexes);

        new DocumentRequestIndexInitializer(mongoTemplate).run(null);

        ArgumentCaptor<Index> requestCaptor = ArgumentCaptor.forClass(Index.class);
        verify(requestIndexes, times(4)).ensureIndex(requestCaptor.capture());
        Map<String, Document> requestByName = byName(requestCaptor.getAllValues());
        assertThat(requestByName).containsOnlyKeys("organization_requestedAt", "organization_requester_requestedAt",
                "organization_recipient_requestedAt", "status_direction_requiredBy");
        assertThat(requestByName.get("status_direction_requiredBy"))
                .isEqualTo(new Document("status", 1).append("requestDirection", 1).append("requiredBy", 1));

        ArgumentCaptor<Index> reminderCaptor = ArgumentCaptor.forClass(Index.class);
        verify(reminderIndexes).ensureIndex(reminderCaptor.capture());
        Index unique = reminderCaptor.getValue();
        assertThat(unique.getIndexOptions().get("name")).isEqualTo("document_request_reminder_unique");
        assertThat(unique.getIndexOptions().get("unique")).isEqualTo(true);
        assertThat(unique.getIndexKeys())
                .isEqualTo(new Document("documentRequestId", 1).append("reminderType", 1).append("requiredBy", 1));
    }

    private Map<String, Document> byName(List<Index> indexes) {
        return indexes.stream().collect(Collectors.toMap(
                index -> (String) index.getIndexOptions().get("name"), Index::getIndexKeys));
    }
}
