package com.waypoint.service.geocoding;

import java.util.Optional;

/**
 * Resolves free-text location strings (e.g. "Austin, TX 78701") to
 * coordinates. Implementations must be best-effort: a lookup failure
 * (unresolvable address, network error, provider outage) returns
 * Optional.empty() rather than throwing, so callers can always save a job
 * with null coordinates instead of blocking the save.
 */
public interface GeocodingService {

    Optional<GeoCoordinates> geocode(String locationText);
}
