import { useTheme } from '../../context/ThemeContext';
import './ThemeToggle.css';
import StrokeIcon from './StrokeIcon';

/**
 * ThemeToggle - accessible button that switches between light and dark themes.
 */
export default function ThemeToggle({
  showLabel = true,
  iconOnly = false,
  className = '',
  id = 'theme-toggle-btn'
}) {
  const { theme, toggleTheme } = useTheme();
  const isDark = theme === 'dark';

  const classes = [
    'wp-theme-toggle',
    iconOnly && 'wp-theme-toggle--icon-only',
    className
  ].filter(Boolean).join(' ');

  const title = `Switch to ${isDark ? 'light' : 'dark'} theme`;

  return (
    <button
      type="button"
      id={id}
      className={classes}
      onClick={toggleTheme}
      aria-label={title}
      title={title}
    >
      <span className="wp-theme-toggle__icon" aria-hidden="true">
        <StrokeIcon name={isDark ? 'sun' : 'moon'} />
      </span>
      {showLabel && !iconOnly && (
        <span className="wp-theme-toggle__label">
          {isDark ? 'Light' : 'Dark'}
        </span>
      )}
    </button>
  );
}
