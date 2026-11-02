package com.waypoint.dto.myhr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WavyChatRequest(
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 500) String route,
        @Size(max = 10) List<@NotNull @Valid Turn> history) {
    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 2000) String text) { }
}
