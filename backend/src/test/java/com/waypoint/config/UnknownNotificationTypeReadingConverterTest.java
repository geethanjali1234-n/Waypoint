package com.waypoint.config;

import com.mongodb.client.MongoClients;
import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.repository.NotificationRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnknownNotificationTypeReadingConverterTest {

    private final MongoCustomConversions conversions = new MongoConversionConfig().mongoCustomConversions();

    private MappingMongoConverter converter() {
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }

    private Document stored(String id, String type) {
        return new Document("_id", id)
                .append("recipientUserId", "user-1")
                .append("notificationType", type)
                .append("title", "Title")
                .append("message", "Message")
                .append("actionUrl", "/myhr/hr/profile")
                .append("isRead", false)
                .append("createdAt", new Date());
    }

    @Test
    void unknownStoredType_isReadAsUnknown_withoutFailingTheDocument() {
        Notification notification = converter().read(Notification.class, stored("n-1", "IMMIGRATION_DEADLINE_REMINDER"));

        assertThat(notification.getNotificationType()).isEqualTo(NotificationType.UNKNOWN);
        assertThat(notification.getId()).isEqualTo("n-1");
        assertThat(notification.getTitle()).isEqualTo("Title");
        assertThat(notification.getActionUrl()).isEqualTo("/myhr/hr/profile");
    }

    @Test
    void everyKnownStoredType_stillReadsAsItself() {
        for (NotificationType type : NotificationType.values()) {
            Notification notification = converter().read(Notification.class, stored("n", type.name()));
            assertThat(notification.getNotificationType()).isEqualTo(type);
        }
    }

    @Test
    void knownAndUnknownDocuments_canBothBeReadFromTheSameResultSet() {
        Notification known = converter().read(Notification.class, stored("n-1", "TIMESHEET_SUBMITTED"));
        Notification unknown = converter().read(Notification.class, stored("n-2", "IMMIGRATION_DEADLINE_REMINDER"));

        assertThat(known.getNotificationType()).isEqualTo(NotificationType.TIMESHEET_SUBMITTED);
        assertThat(unknown.getNotificationType()).isEqualTo(NotificationType.UNKNOWN);
    }

    @Test
    void unrelatedMappingProblems_stillSurface() {
        Document malformed = stored("n-3", "TIMESHEET_SUBMITTED").append("createdAt", "not-a-date");

        assertThatThrownBy(() -> converter().read(Notification.class, malformed)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void writingKnownType_isUnchanged() {
        Document written = new Document();
        converter().write(
                Notification.builder().id("n-1").notificationType(NotificationType.TIMESHEET_SUBMITTED).build(), written);

        assertThat(written.get("notificationType")).isEqualTo("TIMESHEET_SUBMITTED");
    }

    @Test
    void readStateUpdateInputs_mapToTheShapesStoredInTheCollection() {
        MappingMongoConverter converter = converter();
        String id = "0123456789abcdef01234567";

        Document mapped = new QueryMapper(converter).getMappedObject(
                new Document("_id", id).append("recipientUserId", "user-1").append("isRead", new Document("$ne", true)),
                converter.getMappingContext().getPersistentEntity(Notification.class));

        assertThat(mapped.get("_id")).isEqualTo(new ObjectId(id));
        assertThat(mapped.get("recipientUserId")).isEqualTo("user-1");
        assertThat(converter.convertToMongoType(LocalDateTime.now())).isInstanceOf(Date.class);
    }

    @Test
    void notificationRepositoryQueriesParseAtStartup() {
        // Builds the repository proxy, which validates the @Query/@Update read-state methods; it opens no connection.
        MongoTemplate template = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(MongoClients.create("mongodb://localhost:1/unused"), "unused"));
        assertThat(new MongoRepositoryFactory(template).getRepository(NotificationRepository.class)).isNotNull();
    }
}
