import { useEffect, useReducer, useRef, useState } from 'react';
import { INITIAL_MASCOT_STATE, mascotReducer } from './mascotMachine';

const HOLD_DURATION = { greeting: 750, speaking: 850, success: 700, confused: 1400 };

export default function useWaviMascot() {
  const [machine, send] = useReducer(mascotReducer, INITIAL_MASCOT_STATE);
  const firstBlink = useRef(true);
  const [visible, setVisible] = useState(() => !document.hidden);
  const [reducedMotion, setReducedMotion] = useState(() => window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches || false);

  useEffect(() => {
    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const visibilityChanged = () => { setVisible(!document.hidden); send({ type: 'BLINK', closed: false }); };
    const motionChanged = () => { setReducedMotion(motion?.matches || false); send({ type: 'BLINK', closed: false }); };
    document.addEventListener('visibilitychange', visibilityChanged);
    motion?.addEventListener?.('change', motionChanged);
    return () => {
      document.removeEventListener('visibilitychange', visibilityChanged);
      motion?.removeEventListener?.('change', motionChanged);
    };
  }, []);

  useEffect(() => {
    if (!visible || !HOLD_DURATION[machine.expression]) return;
    const timer = setTimeout(() => send({ type: 'SETTLE' }), HOLD_DURATION[machine.expression]);
    return () => clearTimeout(timer);
  }, [visible, machine.expression, machine.revision]);

  useEffect(() => {
    if (!visible || machine.expression !== 'idle') return;
    const timer = setTimeout(() => send({ type: 'REST' }), 45000);
    return () => clearTimeout(timer);
  }, [visible, machine.expression, machine.revision]);

  useEffect(() => {
    if (!visible || reducedMotion || machine.expression === 'sleeping') return;
    let blinkTimer;
    let reopenTimer;
    const schedule = () => {
      const delay = firstBlink.current ? 900 : 3000 + Math.random() * 3500;
      blinkTimer = setTimeout(() => {
        firstBlink.current = false;
        send({ type: 'BLINK', closed: true });
        reopenTimer = setTimeout(() => { send({ type: 'BLINK', closed: false }); schedule(); }, 170);
      }, delay);
    };
    schedule();
    return () => { clearTimeout(blinkTimer); clearTimeout(reopenTimer); };
  }, [visible, reducedMotion, machine.expression]);

  return { animation: { state: machine.expression, blinking: machine.blinking, paused: !visible, reducedMotion }, send };
}
