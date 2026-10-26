package com.waypoint.dto.myhr;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * One position of a signature on an I-983 (a document can be signed in
 * several places). page is 1-based; x/y/width/height are fractions (0-1) of
 * the page as displayed, measured from its top-left corner.
 */
public record I983SignaturePlacement(
        @Min(1) int page,
        @DecimalMin("0.0") @DecimalMax("1.0") double x,
        @DecimalMin("0.0") @DecimalMax("1.0") double y,
        @DecimalMin("0.01") @DecimalMax("1.0") double width,
        @DecimalMin("0.005") @DecimalMax("1.0") double height) {
}
