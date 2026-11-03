import { useEffect, useState } from 'react';

// Matches the 768px breakpoint used by documentRequestsMobile.css.
const MOBILE_QUERY = '(max-width: 768px)';

const matches = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  && window.matchMedia(MOBILE_QUERY).matches;

// True on phone-width screens. Falls back to desktop when matchMedia is unavailable.
export default function useIsMobileViewport() {
  const [isMobile, setIsMobile] = useState(matches);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const query = window.matchMedia(MOBILE_QUERY);
    const update = () => setIsMobile(query.matches);
    update();
    query.addEventListener?.('change', update);
    return () => query.removeEventListener?.('change', update);
  }, []);
  return isMobile;
}
