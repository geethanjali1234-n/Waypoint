import { useEffect, useState } from 'react';

export default function WaviFace({ active = true }) {
  const [blinking, setBlinking] = useState(false);

  useEffect(() => {
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let timer;
    let closingTimer;
    let stopped = false;
    const schedule = (delay = 2800 + Math.random() * 3700, doubleBlink = false) => {
      timer = setTimeout(() => {
        if (stopped || !active || document.hidden || reducedMotion?.matches) return;
        setBlinking(true);
        closingTimer = setTimeout(() => {
          setBlinking(false);
          // Occasionally follow a blink with a second, quick blink.
          const repeat = !doubleBlink && Math.random() < .12;
          schedule(repeat ? 180 : undefined, repeat);
        }, 190);
      }, delay);
    };
    const restart = () => {
      clearTimeout(timer);
      clearTimeout(closingTimer);
      setBlinking(false);
      if (active && !document.hidden && !reducedMotion?.matches) schedule();
    };
    restart();
    document.addEventListener('visibilitychange', restart);
    reducedMotion?.addEventListener?.('change', restart);
    return () => {
      stopped = true;
      clearTimeout(timer);
      clearTimeout(closingTimer);
      document.removeEventListener('visibilitychange', restart);
      reducedMotion?.removeEventListener?.('change', restart);
    };
  }, [active]);

  return <span className={`wp-wavi-face ${blinking ? 'is-blinking' : ''}`} aria-hidden="true">
    <img src="/images/assistant/waypoint-face.png" alt="" />
    <span className="wp-wavi-face__eyelid wp-wavi-face__eyelid--left" />
    <span className="wp-wavi-face__eyelid wp-wavi-face__eyelid--right" />
  </span>;
}
