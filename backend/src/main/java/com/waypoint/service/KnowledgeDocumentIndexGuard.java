package com.waypoint.service;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Blocks knowledge approvals unless knowledge_documents has the unique partial index that keeps a
 * stored document in at most one LIVE entry:
 *
 * <pre>
 * { organizationId: 1, sourceDocumentIds: 1 }, unique, partialFilterExpression { status: "LIVE" }
 * </pre>
 *
 * <p>Without that index, two concurrent approvals of the same document could both succeed, so any
 * doubt fails closed: a missing or different index, or indexes that cannot be read. Matching is by
 * definition, not name. The index is re-read on every check because it can be dropped at any time.
 * This class only lists indexes; it never creates, changes or drops one, and never creates the
 * collection. Revocation must not depend on it.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentIndexGuard {

    /** Name the approved setup step will give the index; informational, since matching is by definition. */
    public static final String INDEX_NAME = "uniq_live_source_document";
    public static final List<String> INDEX_KEYS = List.of("organizationId", "sourceDocumentIds");
    private static final String LIVE = KnowledgeDocumentStatus.LIVE.name();

    static final String UNAVAILABLE =
            "Knowledge approvals are unavailable until the required database index is installed";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentIndexGuard.class);

    private final MongoOperations mongoOperations;

    /** The required partialFilterExpression, as a fresh copy for the future setup step. */
    public static Document partialFilterExpression() {
        return new Document("status", LIVE);
    }

    /** Returns normally only if the required index exists; otherwise throws BusinessException. */
    public void requireUniqueLiveSourceIndex() {
        List<IndexInfo> indexes;
        try {
            indexes = mongoOperations.indexOps(KnowledgeDocument.class).getIndexInfo();
        } catch (RuntimeException failure) {
            // Class name only: driver messages can include connection details.
            log.warn("Could not read knowledge_documents indexes ({}); knowledge approvals are blocked",
                    failure.getClass().getSimpleName());
            throw new BusinessException(UNAVAILABLE);
        }
        if (indexes == null || indexes.stream().noneMatch(KnowledgeDocumentIndexGuard::isRequiredIndex)) {
            log.warn("knowledge_documents has no unique LIVE source-document index; knowledge approvals are blocked");
            throw new BusinessException(UNAVAILABLE);
        }
    }

    /**
     * Unique, keyed exactly on ascending organizationId then sourceDocumentIds, and partial on
     * status LIVE alone. A hidden index still enforces uniqueness, so it qualifies.
     */
    static boolean isRequiredIndex(IndexInfo index) {
        return index != null
                && index.isUnique()
                && !index.isSparse()
                && !index.isWildcard()
                && hasRequiredKeys(index.getIndexFields())
                && isLiveOnlyFilter(index.getPartialFilterExpression());
    }

    private static boolean hasRequiredKeys(List<IndexField> fields) {
        if (fields == null || fields.size() != INDEX_KEYS.size()) {
            return false;
        }
        for (int position = 0; position < fields.size(); position++) {
            IndexField field = fields.get(position);
            if (!INDEX_KEYS.get(position).equals(field.getKey())
                    || field.getDirection() != Sort.Direction.ASC
                    || field.isGeo() || field.isText() || field.isHashed() || field.isWildcard()) {
                return false;
            }
        }
        return true;
    }

    /** Accepts { status: "LIVE" } or the equivalent { status: { $eq: "LIVE" } } and nothing broader or narrower. */
    private static boolean isLiveOnlyFilter(String partialFilterExpression) {
        if (partialFilterExpression == null || partialFilterExpression.isBlank()) {
            return false;
        }
        Document filter;
        try {
            filter = Document.parse(partialFilterExpression);
        } catch (RuntimeException unparseable) {
            return false;
        }
        if (!filter.keySet().equals(Set.of("status"))) {
            return false;
        }
        Object status = filter.get("status");
        return LIVE.equals(status)
                || (status instanceof Document condition
                && condition.keySet().equals(Set.of("$eq"))
                && LIVE.equals(condition.get("$eq")));
    }
}
