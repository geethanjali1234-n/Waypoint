/**
 * Waypoint shared component library.
 * Imports the shared design tokens once and re-exports every component
 * grouped by category: global, form, feedback, navigation, layout, domain.
 */
import './styles/tokens.css';

export * from './global';
export * from './form';
export * from './feedback';
export * from './navigation';
export * from './layout';
export * from './domain';

export { default as FloatingAssistant } from './chatbot/FloatingAssistant';
