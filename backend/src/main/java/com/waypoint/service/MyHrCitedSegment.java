package com.waypoint.service;

import java.util.List;

/**
 * One piece of a provider's answer, in answer order, with the document passages the provider says support it.
 * Provider output: nothing here is trusted until MyHrKnowledgeGrounding has checked each citation against the
 * documents that were actually supplied.
 */
public record MyHrCitedSegment(String text, List<Citation> citations) {

    public MyHrCitedSegment {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Answer text stays out of logs. */
    @Override
    public String toString() {
        return "MyHrCitedSegment[textLength=" + (text == null ? 0 : text.length()) + ", citations=" + citations.size() + "]";
    }

    /** A passage citation as the provider reported it: 0-based document index, 0-based end-exclusive characters. */
    public record Citation(String type, String citedText, Integer documentIndex, Integer startCharIndex,
                           Integer endCharIndex) {

        @Override
        public String toString() {
            return "Citation[type=" + type + ", documentIndex=" + documentIndex + "]";
        }
    }
}
