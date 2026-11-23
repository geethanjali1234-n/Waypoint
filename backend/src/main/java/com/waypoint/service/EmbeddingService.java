package com.waypoint.service;

import java.util.List;

public interface EmbeddingService {

    enum InputType {
        DOCUMENT,
        QUERY
    }

    List<Double> embed(String text, InputType inputType);

    List<List<Double>> embedBatch(List<String> texts, InputType inputType);

    int dimensions();

    /** The configured model, exactly as sent with each request; vectors are comparable only within one model. */
    String model();

    class EmbeddingException extends RuntimeException {
        public EmbeddingException() { super(); }
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(Throwable cause) { super(cause); }
    }

    class EmbeddingUnavailableException extends EmbeddingException {
        public EmbeddingUnavailableException() { super(); }
        public EmbeddingUnavailableException(Throwable cause) { super(cause); }
    }

    /** The provider answered HTTP 429. Still unavailable to every caller; the distinct type only names the cause. */
    class EmbeddingRateLimitedException extends EmbeddingUnavailableException {}

    class EmbeddingConfigurationException extends EmbeddingException {}

    class EmbeddingRequestException extends EmbeddingException {}

    class EmbeddingDimensionMismatchException extends EmbeddingException {
        public EmbeddingDimensionMismatchException(int expected, int actual) {
            super("Expected " + expected + " embedding dimensions but received " + actual);
        }
    }
}
