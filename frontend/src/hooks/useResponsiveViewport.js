import { useEffect, useState } from 'react';

/**
 * Reactive Viewport Hook
 * Listens to media queries and viewport changes reactively.
 * @returns {{ isMobile: boolean, isTablet: boolean, isDesktop: boolean, width: number }}
 */
export default function useResponsiveViewport() {
  const [viewport, setViewport] = useState(() => {
    if (typeof window === 'undefined') {
      return { isMobile: false, isTablet: false, isDesktop: true, width: 1200 };
    }
    const width = window.innerWidth;
    return {
      isMobile: width <= 768,
      isTablet: width > 768 && width <= 1024,
      isDesktop: width > 1024,
      width,
    };
  });

  useEffect(() => {
    const mobileQuery = window.matchMedia('(max-width: 768px)');
    const tabletQuery = window.matchMedia('(min-width: 769px) and (max-width: 1024px)');

    const updateMatches = () => {
      const width = window.innerWidth;
      setViewport({
        isMobile: mobileQuery.matches,
        isTablet: tabletQuery.matches,
        isDesktop: !mobileQuery.matches && !tabletQuery.matches,
        width,
      });
    };

    updateMatches();

    mobileQuery.addEventListener('change', updateMatches);
    tabletQuery.addEventListener('change', updateMatches);
    window.addEventListener('resize', updateMatches);

    return () => {
      mobileQuery.removeEventListener('change', updateMatches);
      tabletQuery.removeEventListener('change', updateMatches);
      window.removeEventListener('resize', updateMatches);
    };
  }, []);

  return viewport;
}
