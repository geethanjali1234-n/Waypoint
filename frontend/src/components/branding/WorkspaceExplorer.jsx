import { useState } from 'react';
import { useUiVariant } from '../../context/uiVariant';
import './WorkspaceExplorer.css';

const AREAS = [
  ['People', 'Keep employee profiles and reporting relationships together.'],
  ['Time', 'Enter hours, review periods, and follow timesheet approvals.'],
  ['Documents', 'Request documents and track each request in one place.'],
];

/** An interactive product introduction, with no fabricated user progress. */
export default function WorkspaceExplorer() {
  const { activeVariant } = useUiVariant();
  const [selected, setSelected] = useState(0);
  return <section className="wp-workspace-explorer" aria-label="Explore Waypoint">
    <span className="wp-workspace-explorer__label">{activeVariant === 'v2' ? 'CHOOSE A SECTOR' : activeVariant === 'v3' ? 'What is on your mind?' : 'Explore your workspace'}</span>
    {activeVariant === 'v1' ? <div className="wp-workspace-explorer__directory">
      {AREAS.map(([name, description]) => <details key={name}><summary>{name}</summary><p>{description}</p></details>)}
    </div> : <>
      <div className="wp-workspace-explorer__choices">
        {AREAS.map(([name], index) => <button type="button" key={name} aria-pressed={selected === index} onClick={() => setSelected(index)}>
          <span aria-hidden="true">{activeVariant === 'v2' ? `0${index + 1}` : ['○', '◷', '▱'][index]}</span>{name}
        </button>)}
      </div>
      <p aria-live="polite">{AREAS[selected][1]}</p>
      {activeVariant === 'v3' && <button className="wp-workspace-explorer__next" type="button" onClick={() => setSelected((selected + 1) % AREAS.length)}>Show me another <span aria-hidden="true">↻</span></button>}
    </>}
  </section>;
}
