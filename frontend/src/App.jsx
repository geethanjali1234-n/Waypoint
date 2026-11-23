import AppRouter from "./routes/AppRouter";
import { ThemeProvider } from "./context/ThemeContext";
import UiVariantProvider from './context/UiVariantProvider';
import AppUpdateNotification from './components/global/AppUpdateNotification';
import { WaviConversationProvider } from './components/chatbot/WaviConversationContext';
import './components/styles/uiVariants.css';

function App() {
  return (
    <ThemeProvider>
      <UiVariantProvider>
        <WaviConversationProvider><AppRouter /></WaviConversationProvider>
        <AppUpdateNotification />
      </UiVariantProvider>
    </ThemeProvider>
  );
}

export default App;
