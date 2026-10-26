import { useEffect } from 'react';
import { DEFAULT_UI_VARIANT, UiVariantContext } from './uiVariant';

// Compatibility provider: saved experiments no longer change the production UI.
const value = { activeVariant: DEFAULT_UI_VARIANT, setActiveVariant: () => {} };
export default function UiVariantProvider({ children }) {
  useEffect(() => {
    document.documentElement.dataset.uiVariant = DEFAULT_UI_VARIANT;
  }, []);
  return <UiVariantContext.Provider value={value}>{children}</UiVariantContext.Provider>;
}
