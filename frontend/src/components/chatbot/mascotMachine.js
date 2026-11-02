export const MASCOT_STATES = ['idle', 'greeting', 'attentive', 'thinking', 'speaking', 'success', 'confused', 'sleeping'];
export const INITIAL_MASCOT_STATE = { expression: 'idle', pending: false, hasInput: false, blinking: false, revision: 0 };

export function mascotReducer(state, event) {
  const next = (changes) => ({ ...state, ...changes, blinking: false, revision: state.revision + 1 });
  switch (event.type) {
    case 'OPEN_CHAT':
    case 'GREET': return state.pending ? state : next({ expression: 'greeting' });
    case 'WAKE': return state.expression === 'sleeping' ? next({ expression: state.hasInput ? 'attentive' : 'idle' }) : state;
    case 'INPUT_CHANGED': return next({ hasInput: event.hasInput, expression: state.pending ? 'thinking' : event.hasInput ? 'attentive' : 'idle' });
    case 'REQUEST_SENT': return next({ expression: 'thinking', pending: true, hasInput: false });
    case 'RESPONSE_RECEIVED': return next({ expression: event.error || event.needsClarification ? 'confused' : 'speaking', pending: false });
    // A verified read is not a completed task. Only a confirmed mutation may celebrate.
    case 'TASK_CONFIRMED': return event.confirmed === true && event.kind === 'mutation' && !state.pending ? next({ expression: 'success' }) : state;
    case 'SETTLE': return next({ expression: state.pending ? 'thinking' : state.hasInput ? 'attentive' : 'idle' });
    case 'REST': return !state.pending && !state.hasInput ? next({ expression: 'sleeping' }) : state;
    case 'BLINK': return { ...state, blinking: state.expression !== 'sleeping' && event.closed };
    case 'RESET': return { ...INITIAL_MASCOT_STATE, revision: state.revision + 1 };
    default: return state;
  }
}
