package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring must build the transaction helper with its production constructor (the MongoDB one), not the test-only
 * no-argument constructor - otherwise replacing an organization approval has no transaction. A mocked database factory
 * stands in for MongoDB; no connection is made.
 */
class ExternalProcessingApprovalTransactionsWiringTest {

    @Test
    void springUsesTheMongoConstructorSoReplacementRunsInARealTransaction() {
        MongoDatabaseFactory factory = mock(MongoDatabaseFactory.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MongoDatabaseFactory.class, () -> factory);
            context.register(ExternalProcessingApprovalTransactions.class);
            context.refresh();

            ExternalProcessingApprovalTransactions helper = context.getBean(ExternalProcessingApprovalTransactions.class);
            Object template = ReflectionTestUtils.getField(helper, "transactions");

            assertThat(template).isInstanceOf(TransactionTemplate.class);
            Object manager = ((TransactionTemplate) template).getTransactionManager();
            assertThat(manager).isInstanceOf(MongoTransactionManager.class);
            assertThat(((MongoTransactionManager) manager).getDatabaseFactory()).isSameAs(factory);
        }
    }
}
