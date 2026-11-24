package com.waypoint.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs one approval change as a single MongoDB multi-document transaction (Atlas replica set), so replacing an
 * organization approval either commits both records or neither. Deliberately local to approvals: it is not a
 * transaction manager bean, so no other part of Waypoint changes behaviour.
 */
@Component
public class ExternalProcessingApprovalTransactions {

    private final TransactionTemplate transactions;

    @Autowired
    public ExternalProcessingApprovalTransactions(MongoDatabaseFactory databaseFactory) {
        this.transactions = new TransactionTemplate(new MongoTransactionManager(databaseFactory));
    }

    /** For tests: a runner with its own commit and rollback behaviour. */
    protected ExternalProcessingApprovalTransactions() {
        this.transactions = null;
    }

    /** Runs work in one transaction; any exception rolls every write back and is rethrown. */
    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
