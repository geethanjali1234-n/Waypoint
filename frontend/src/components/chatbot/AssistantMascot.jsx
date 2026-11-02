import WaviMascot from './WaviMascot';

export default function AssistantMascot({ animation, active = true, onGreet, onWake }) {
  const status = animation?.state === 'thinking' ? 'Finding your answer...'
    : animation?.state === 'attentive' ? 'Ready when you are.'
      : animation?.state === 'sleeping' ? 'Here when you need a hand.' : 'Tap to say hello';
  return <div className="wp-assistant-mascot">
    <button type="button" className="wp-assistant-mascot__button" aria-label="Say hello to the Waypoint robot"
      onClick={onGreet} onFocus={onWake}>
      <WaviMascot animation={animation} active={active} onWake={onWake} />
    </button>
    <span className="wp-assistant-mascot__status">{status}</span>
  </div>;
}
