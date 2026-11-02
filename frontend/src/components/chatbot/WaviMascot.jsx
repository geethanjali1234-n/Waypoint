import { useEffect, useId, useRef } from 'react';
import './WaviMascot.css';

const IDLE = { state: 'idle', blinking: false, paused: false, reducedMotion: false };

/** Layered, decorative character. Put it inside a named button for activation. */
export default function WaviMascot({ animation = IDLE, variant = 'full', active = true, onWake, className = '' }) {
  const id = useId().replace(/:/g, '');
  const root = useRef(null);
  const frame = useRef(null);
  useEffect(() => () => window.cancelAnimationFrame?.(frame.current), []);
  const resetLook = () => {
    window.cancelAnimationFrame?.(frame.current);
    root.current?.style.removeProperty('--wavy-look-x');
    root.current?.style.removeProperty('--wavy-look-y');
    root.current?.style.removeProperty('--wavy-head-tilt');
  };
  const look = (event) => {
    if (event.pointerType !== 'mouse' || animation.reducedMotion || animation.paused || !active) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    if (animation.state === 'sleeping') onWake?.();
    const x = Math.max(-1, Math.min(1, ((event.clientX - bounds.left) / bounds.width - .5) * 2));
    const y = Math.max(-1, Math.min(1, ((event.clientY - bounds.top) / bounds.height - .5) * 2));
    window.cancelAnimationFrame?.(frame.current);
    // DOM variables avoid a React render on every pointer movement.
    frame.current = window.requestAnimationFrame(() => {
      root.current?.style.setProperty('--wavy-look-x', `${x * 3}px`);
      root.current?.style.setProperty('--wavy-look-y', `${y * 2}px`);
      root.current?.style.setProperty('--wavy-head-tilt', `${x * 4}deg`);
    });
  };
  const url = (name) => `url(#${id}-${name})`;

  return <span ref={root} className={`wavy-mascot wavy-mascot--${variant} ${className}`}
    data-state={animation.state} data-blinking={Boolean(animation.blinking && active && !animation.paused && !animation.reducedMotion)} data-paused={!active || animation.paused}
    data-reduced-motion={animation.reducedMotion} aria-hidden="true"
    onPointerEnter={() => onWake?.()} onPointerMove={look} onPointerLeave={resetLook}>
    <svg viewBox={variant === 'compact' ? '14 0 212 180' : '0 0 240 260'} focusable="false" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-shell`} x1="0" y1="0" x2=".9" y2="1"><stop stopColor="#fffef9" /><stop offset=".55" stopColor="#eef3ee" /><stop offset="1" stopColor="#b8cfc4" /></linearGradient>
        <linearGradient id={`${id}-screen`} x2=".8" y2="1"><stop stopColor="#125744" /><stop offset="1" stopColor="#05261f" /></linearGradient>
        <linearGradient id={`${id}-mint`} x2="1" y2="1"><stop stopColor="#ddffee" /><stop offset="1" stopColor="#71e5b2" /></linearGradient>
      </defs>
      {variant === 'full' && <>
        <ellipse cx="120" cy="246" rx="53" ry="7" fill="#064e3b" opacity=".13" />
        <g className="wavy-mascot__body">
          <rect x="104" y="154" width="32" height="24" rx="10" fill="#064e3b" />
          <path d="M76 186 Q80 166 120 166 Q160 166 164 186 L168 226 Q160 244 120 244 Q80 244 72 226Z" fill={url('shell')} stroke="#b5d6c6" strokeWidth="2" />
          <rect x="94" y="185" width="52" height="43" rx="13" fill="#0e4d3a" />
          <text x="120" y="215" textAnchor="middle" fontFamily="Georgia,serif" fontSize="29" fontWeight="700" fill="#f5f1e8">W</text>
          <path d="M108 222H132" stroke="#a7f3d0" strokeWidth="2.5" strokeLinecap="round" />
        </g>
        <g className="wavy-mascot__hand wavy-mascot__hand--left">
          <path d="M78 183 Q57 177 53 201 L56 220" fill="none" stroke="#c4d8ce" strokeWidth="19" strokeLinecap="round" />
          <ellipse cx="56" cy="221" rx="14" ry="17" transform="rotate(-20 56 221)" fill={url('shell')} stroke="#a5c4b5" />
        </g>
        <g className="wavy-mascot__hand wavy-mascot__hand--right">
          <path d="M162 184 Q181 187 186 171" fill="none" stroke="#dbe8df" strokeWidth="18" strokeLinecap="round" />
          <g className="wavy-mascot__palm">
            <path d="M181 164 L176 149 M187 162 L188 144 M194 164 L200 149" stroke="#074331" strokeWidth="7" strokeLinecap="round" />
            <ellipse cx="187" cy="172" rx="14" ry="17" transform="rotate(16 187 172)" fill={url('shell')} stroke="#a5c4b5" />
          </g>
        </g>
      </>}
      <g className="wavy-mascot__head">
        <g className="wavy-mascot__antenna">
          <path d="M120 44V25" stroke="#0e4d3a" strokeWidth="7" strokeLinecap="round" />
          <circle className="wavy-mascot__antenna-glow" cx="120" cy="19" r="14" fill="#a7f3d0" opacity=".22" />
          <circle className="wavy-mascot__antenna-light" cx="120" cy="19" r="8" fill={url('mint')} stroke="#4ab98b" strokeWidth="2" />
        </g>
        <rect x="24" y="80" width="22" height="53" rx="11" fill={url('shell')} stroke="#9dcab5" strokeWidth="2" />
        <rect x="194" y="80" width="22" height="53" rx="11" fill={url('shell')} stroke="#9dcab5" strokeWidth="2" />
        <path d="M36 83 Q36 42 77 40 H163 Q204 42 204 83 V128 Q204 163 166 167 H74 Q36 163 36 128Z" fill={url('shell')} stroke="#a9cbbb" strokeWidth="2" />
        <path d="M48 86 Q48 56 79 55 H161 Q192 56 192 86 V123 Q192 149 162 152 H78 Q48 149 48 123Z" fill={url('screen')} stroke="#194c3a" strokeWidth="3" />
        <path d="M64 70 Q95 56 135 64" fill="none" stroke="#ffffff" strokeOpacity=".1" strokeWidth="6" strokeLinecap="round" />
        <g className="wavy-mascot__gaze">
          {[88, 152].map((x, index) => <g key={x} transform={`translate(${x} 104)`}>
            <g className={`wavy-mascot__eye wavy-mascot__eye--${index}`}>
              <ellipse className="wavy-mascot__eye-open" rx="9" ry="11" fill="#a7f3d0" />
              <path className="wavy-mascot__eye-happy" d="M-12 3Q0-15 12 3" fill="none" stroke="#a7f3d0" strokeWidth="7" strokeLinecap="round" />
              <path className="wavy-mascot__eye-rest" d="M-10 2Q0 8 10 2" fill="none" stroke="#a7f3d0" strokeWidth="4" strokeLinecap="round" />
            </g>
            <path className={`wavy-mascot__brow wavy-mascot__brow--${index}`} d="M-11-19L11-19" stroke="#a7f3d0" strokeWidth="3" strokeLinecap="round" />
          </g>)}
          <g className="wavy-mascot__mouth" transform="translate(120 132)">
            <path className="wavy-mascot__smile" d="M-12-1Q0 12 12-1" fill="none" stroke="#a7f3d0" strokeWidth="4" strokeLinecap="round" />
            <ellipse className="wavy-mascot__talk" rx="7" ry="5" fill="#a7f3d0" />
            <path className="wavy-mascot__uncertain" d="M-9 3Q-2-3 9 1" fill="none" stroke="#a7f3d0" strokeWidth="3" strokeLinecap="round" />
          </g>
        </g>
      </g>
      <g className="wavy-mascot__success-marks" fill="#a7f3d0">
        <path d="M31 38l3-7 3 7 7 3-7 3-3 7-3-7-7-3z" />
        <path d="M207 39l2-5 2 5 5 2-5 2-2 5-2-5-5-2z" />
      </g>
    </svg>
  </span>;
}
