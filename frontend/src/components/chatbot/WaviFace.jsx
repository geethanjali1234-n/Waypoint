import WaviMascot from './WaviMascot';

// Compatibility wrapper for existing compact-avatar consumers.
export default function WaviFace({ active = true, animation }) {
  return <WaviMascot variant="compact" active={active} animation={animation} />;
}
