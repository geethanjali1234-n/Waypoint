import { useCallback, useState } from "react";

/**
 * Thin wrapper around the browser Geolocation API. UI-agnostic by design -
 * callers decide what to do on denial/unsupported (e.g. show a toast, reset
 * a filter) rather than this hook baking in page-specific behavior.
 */
export default function useGeolocation() {
  const [coords, setCoords] = useState(null);

  const requestLocation = useCallback(() => {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) {
        reject(new Error("UNSUPPORTED"));
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (position) => {
          const next = { lat: position.coords.latitude, lng: position.coords.longitude };
          setCoords(next);
          resolve(next);
        },
        () => reject(new Error("DENIED"))
      );
    });
  }, []);

  return { coords, requestLocation };
}
