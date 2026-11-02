import { act, renderHook, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { INITIAL_MASCOT_STATE, mascotReducer } from '../../components/chatbot/mascotMachine';
import useWaviMascot from '../../components/chatbot/useWaviMascot';
import WaviMascot from '../../components/chatbot/WaviMascot';

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

it('keeps thinking exclusive while a request is pending', () => {
  let state = mascotReducer(INITIAL_MASCOT_STATE, { type: 'REQUEST_SENT' });
  for (const event of [{ type: 'GREET' }, { type: 'INPUT_CHANGED', hasInput: true }, { type: 'SETTLE' }]) {
    state = mascotReducer(state, event);
    expect(state.expression).toBe('thinking');
  }
  state = mascotReducer(state, { type: 'RESPONSE_RECEIVED' });
  expect(state.expression).toBe('speaking');
  expect(mascotReducer(state, { type: 'SETTLE' }).expression).toBe('attentive');
});

it('celebrates only confirmed mutations, never verified reads or failed tasks', () => {
  for (const event of [{ confirmed: true, kind: 'read' }, { confirmed: false, kind: 'mutation' }]) {
    expect(mascotReducer(INITIAL_MASCOT_STATE, { type: 'TASK_CONFIRMED', ...event }).expression).toBe('idle');
  }
  expect(mascotReducer(INITIAL_MASCOT_STATE, { type: 'TASK_CONFIRMED', confirmed: true, kind: 'mutation' }).expression).toBe('success');
  expect(mascotReducer(INITIAL_MASCOT_STATE, { type: 'RESPONSE_RECEIVED', error: true }).expression).toBe('confused');
});

it('settles one greeting, sleeps after inactivity, and wakes on interaction', () => {
  vi.useFakeTimers();
  const { result } = renderHook(() => useWaviMascot());
  act(() => result.current.send({ type: 'OPEN_CHAT' }));
  expect(result.current.animation.state).toBe('greeting');
  act(() => vi.advanceTimersByTime(750));
  expect(result.current.animation.state).toBe('idle');
  act(() => vi.advanceTimersByTime(45000));
  expect(result.current.animation.state).toBe('sleeping');
  act(() => result.current.send({ type: 'WAKE' }));
  expect(result.current.animation.state).toBe('idle');
});

it('blinks briefly after mounting without changing the primary expression', () => {
  vi.useFakeTimers();
  const { result } = renderHook(() => useWaviMascot());
  act(() => vi.advanceTimersByTime(900));
  expect(result.current.animation).toMatchObject({ state: 'idle', blinking: true });
  act(() => vi.advanceTimersByTime(170));
  expect(result.current.animation.blinking).toBe(false);
});

it('pauses timers when hidden and suppresses blinking with reduced motion', () => {
  vi.useFakeTimers();
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  const { result, unmount } = renderHook(() => useWaviMascot());
  act(() => result.current.send({ type: 'OPEN_CHAT' }));
  act(() => vi.advanceTimersByTime(60000));
  expect(result.current.animation).toMatchObject({ state: 'greeting', paused: true, blinking: false });
  unmount();
  const { container } = render(<WaviMascot animation={{ state: 'thinking', reducedMotion: true, blinking: true }} />);
  expect(container.firstChild).toHaveAttribute('data-blinking', 'false');
  expect(container.firstChild).toHaveAttribute('data-reduced-motion', 'true');
});

it('uses independently drawn parts and unique SVG definitions for repeated mascots', () => {
  const { container } = render(<><WaviMascot /><WaviMascot /></>);
  expect(container.querySelector('img')).toBeNull();
  expect(container.querySelectorAll('.wavy-mascot__hand')).toHaveLength(4);
  const ids = [...container.querySelectorAll('[id]')].map((node) => node.id);
  expect(new Set(ids).size).toBe(ids.length);
});
