package com.waypoint.dto.myhr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Where to place the organization signature on a Signed I-983 - one or
 * more positions, on any pages.
 *
 * documentId is the version the reviewer placed the signatures on (guards
 * against signing a file that was replaced in the meantime). Each placement
 * uses fractions of the displayed page (see I983SignaturePlacement), so it
 * does not depend on the zoom level the reviewer used.
 */
public record I983SignRequest(
        @NotBlank String documentId,
        @NotEmpty @Size(max = 20) List<@Valid I983SignaturePlacement> placements) {
}
