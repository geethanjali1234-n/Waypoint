import { useEffect, useState } from 'react';

export default function AssistantMascot({ active, thinking, listening }) {
  const [waving, setWaving] = useState(false);
  useEffect(() => {
    if (!waving) return;
    const timeout = setTimeout(() => setWaving(false), 1300);
    return () => clearTimeout(timeout);
  }, [waving]);
  const status = thinking ? 'Finding your answer…' : listening ? 'Ready when you are.' : 'Tap to say hello';

  const resetTilt = (event) => {
    event.currentTarget.style.removeProperty('--mascot-x');
    event.currentTarget.style.removeProperty('--mascot-y');
  };

  const followPointer = (event) => {
    if (event.pointerType !== 'mouse') return;
    const bounds = event.currentTarget.getBoundingClientRect();
    event.currentTarget.style.setProperty('--mascot-x', `${((event.clientX - bounds.left) / bounds.width - .5) * 12}deg`);
    event.currentTarget.style.setProperty('--mascot-y', `${((event.clientY - bounds.top) / bounds.height - .5) * -10}deg`);
  };

  return (
    <div className={`wp-assistant-mascot ${active ? 'is-active' : ''} ${thinking ? 'is-thinking' : ''} ${listening ? 'is-listening' : ''}`}>
      <button type="button" className="wp-assistant-mascot__button" aria-label="Say hello to the Waypoint robot"
        onClick={() => setWaving(true)} onPointerMove={followPointer} onPointerLeave={resetTilt} onBlur={resetTilt}>
        <span className="wp-assistant-mascot__halo" aria-hidden="true" />
        <span className={`wp-assistant-mascot__character ${waving ? 'is-waving' : ''}`}
          onAnimationEnd={() => setWaving(false)}>
          <img src="/images/assistant/waypoint-mascot.png" alt="" draggable="false" />
        </span>
        <span className="wp-assistant-mascot__hello" aria-hidden="true" data-visible={waving}>Hello there! ✦</span>
      </button>
      <span className="wp-assistant-mascot__status">{waving ? 'Hello! How can I help?' : status}</span>
    </div>
  );
}
