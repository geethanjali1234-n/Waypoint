package com.waypoint.dto.myhr;

import java.util.List;

public record WavyChatResponse(String text, String mode, String notice,
                               List<Action> actions, List<Source> sources, boolean confirmed) {
    public record Action(String label, String path) { }
    public record Source(String id, String title) { }
    public static WavyChatResponse limited(String notice) {
        return new WavyChatResponse(null, "limited", notice, List.of(), List.of(), false);
    }
}
