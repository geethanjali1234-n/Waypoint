import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import WaviMascot from '../components/chatbot/WaviMascot';
import useWaviMascot from '../components/chatbot/useWaviMascot';
import { MASCOT_STATES } from '../components/chatbot/mascotMachine';
import { ThemeProvider, useTheme } from '../context/ThemeContext';
import '../index.css';
import '../components/styles/enterprise.css';
import '../components/styles/routeLineFoundation.css';
import './WaviMascotPreview.css';

export default function MascotPreview() {
  const [selected, setSelected] = useState('idle');
  const [paused, setPaused] = useState(false);
  const [reduced, setReduced] = useState(false);
  const [replay, setReplay] = useState(0);
  const { animation, send } = useWaviMascot();
  const { theme, toggleTheme } = useTheme();
  const visual = { ...animation, state: selected, paused: paused || animation.paused, reducedMotion: reduced || animation.reducedMotion };

  return <main className="wavy-preview">
    <header><div><p>DEVELOPER PREVIEW</p><h1>Meet Wavy.</h1></div><button type="button" onClick={toggleTheme}>Switch to {theme === 'dark' ? 'light' : 'dark'} mode</button></header>
    <p>Simulated expressions only. This page makes no API calls and performs no workplace actions.</p>
    <section className="wavy-preview__stage" aria-label="Selected expression">
      <button type="button" aria-label="Replay selected expression" onClick={() => setReplay((value) => value + 1)}>
        <WaviMascot key={replay} animation={visual} />
      </button>
      <div><h2>{selected}</h2><p>Move the pointer over the robot to test eye tracking. Click it to replay the expression.</p><WaviMascot variant="compact" animation={visual} /></div>
    </section>
    <div className="wavy-preview__states" aria-label="Expression controls">
      {MASCOT_STATES.map((state) => <button type="button" key={state} aria-pressed={selected === state} onClick={() => { setSelected(state); setReplay((value) => value + 1); }}>{state}</button>)}
    </div>
    <div className="wavy-preview__options">
      <label><input type="checkbox" checked={paused} onChange={(event) => setPaused(event.target.checked)} /> Pause motion</label>
      <label><input type="checkbox" checked={reduced} onChange={(event) => setReduced(event.target.checked)} /> Simulate reduced motion</label>
    </div>
    <section className="wavy-preview__machine">
      <h2>Controller event test</h2><p>Active primary state: <strong>{animation.state}</strong></p>
      <WaviMascot animation={animation} />
      <div className="wavy-preview__states">
        <button type="button" onClick={() => send({ type: 'OPEN_CHAT' })}>Open chat</button>
        <button type="button" onClick={() => send({ type: 'INPUT_CHANGED', hasInput: true })}>Type</button>
        <button type="button" onClick={() => send({ type: 'REQUEST_SENT' })}>Send</button>
        <button type="button" onClick={() => send({ type: 'RESPONSE_RECEIVED' })}>Receive answer</button>
        <button type="button" onClick={() => send({ type: 'RESPONSE_RECEIVED', error: true })}>API error</button>
        <button type="button" onClick={() => send({ type: 'TASK_CONFIRMED', confirmed: true, kind: 'read' })}>Verified read (no celebration)</button>
        <button type="button" onClick={() => send({ type: 'TASK_CONFIRMED', confirmed: true, kind: 'mutation' })}>Simulate confirmed task</button>
        <button type="button" onClick={() => send({ type: 'RESET' })}>Reset</button>
      </div>
    </section>
  </main>;
}

// Vite serves this separate HTML entry in development; the production build uses index.html only.
createRoot(document.getElementById('root')).render(<ThemeProvider><MascotPreview /></ThemeProvider>);
