import { useEffect, useState } from 'react';
import StrokeIcon from '../global/StrokeIcon';
import CommandPalette from './CommandPalette';

/** With no actions supplied, retain the existing My HR registry and search. */
export default function QuickJump({ actions, className = '' }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const shortcut = (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((value) => !value);
      }
    };
    window.addEventListener('keydown', shortcut);
    return () => window.removeEventListener('keydown', shortcut);
  }, []);
  return <>
    <button type="button" className={`wp-workspace__quick-jump ${className}`} onClick={() => setOpen(true)} aria-label="Universal Quick Jump (Press Ctrl+K)" aria-haspopup="dialog" aria-expanded={open}>
      <StrokeIcon name="search" /><span>Quick Jump</span><kbd>Ctrl K</kbd>
    </button>
    <CommandPalette isOpen={open} onClose={() => setOpen(false)} actions={actions} enableEmployeeSearch={actions === undefined} />
  </>;
}
