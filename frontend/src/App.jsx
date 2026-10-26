import AppRouter from "./routes/AppRouter";
import { ThemeProvider } from "./context/ThemeContext";
import UiVariantProvider from './context/UiVariantProvider';
import AppUpdateNotification from './components/global/AppUpdateNotification';
import './components/styles/uiVariants.css';

function App() {
  return (
    <ThemeProvider>
      <UiVariantProvider>
        <AppRouter />
        <AppUpdateNotification />
      </UiVariantProvider>
    </ThemeProvider>
  );
}

export default App;
