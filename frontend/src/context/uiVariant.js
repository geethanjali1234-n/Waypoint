import { createContext, useContext } from 'react';

export const UI_VARIANTS = [
  { id: 'v1', name: 'Professional', tag: 'Clear & focused', desc: 'A structured workspace with compact rows, precise spacing, and quiet details.' },
  { id: 'v2', name: 'Arcade', tag: 'Game-inspired', desc: 'A mission-board layout with bold outlines, numbered portals, and tactile controls.' },
  { id: 'v3', name: 'Playground', tag: 'Fun & interactive', desc: 'An expressive studio with tactile cards, friendly shapes, and a shuffleable workspace.' },
];
export const UI_VARIANT_STORAGE_KEY = 'waypoint_ui_experiment_variant';
export const DEFAULT_UI_VARIANT = 'v1';
export const UiVariantContext = createContext({ activeVariant: DEFAULT_UI_VARIANT, setActiveVariant: () => {} });
export const useUiVariant = () => useContext(UiVariantContext);
