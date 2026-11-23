import { useId, useState } from 'react';
import { COLLAPSE_AFTER, COLLAPSED_ROWS, emphasisParts, structureAnswer } from './waviAnswerFormat';

/** The server's words, with paired "**" emphasis shown as bold. Nothing else is added or inferred. */
function Text({ text }) {
  return emphasisParts(text).map((part, index) => (part.bold ? <strong key={index}>{part.text}</strong> : part.text));
}

function AnswerList({ group }) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  const collapsible = group.rows.length > COLLAPSE_AFTER;
  const visible = collapsible && !expanded ? group.rows.slice(0, COLLAPSED_ROWS) : group.rows;
  return (
    <div className="wp-wavi-group">
      {group.title && <p className="wp-wavi-group__title"><Text text={group.title} /></p>}
      <ul id={listId} className="wp-wavi-rows">
        {visible.map((row, index) => <li key={index} className="wp-wavi-row"><Text text={row} /></li>)}
      </ul>
      {collapsible && (
        <button type="button" className="wp-wavi-toggle" aria-expanded={expanded} aria-controls={listId}
          onClick={() => setExpanded(!expanded)}>
          {expanded ? 'Show fewer' : `Show all ${group.rows.length} records`}
        </button>
      )}
    </div>
  );
}

/**
 * One assistant reply, rendered faithfully: the opening sentence(s) first and prominent, each "- " line on its own
 * row, and closing notes as small text. Long lists open on their first rows with "Show all". Plain replies render as
 * text, exactly as sent.
 */
export default function WaviAnswer({ text }) {
  const { lead, groups, notes } = structureAnswer(text);
  if (groups.length === 0) return <span className="wp-wavi-plain"><Text text={text} /></span>;
  return (
    <div className="wp-wavi-answer">
      {lead.map((line, index) => <p key={index} className="wp-wavi-answer__lead"><Text text={line} /></p>)}
      {groups.map((group, index) => <AnswerList key={index} group={group} />)}
      {notes.map((note, index) => <p key={index} className="wp-wavi-note"><Text text={note} /></p>)}
    </div>
  );
}
