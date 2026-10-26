import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MobileAppBanner from './MobileAppBanner';

describe('MobileAppBanner', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('renders banner when forceShow is true', () => {
    render(<MobileAppBanner forceShow={true} />);

    expect(screen.getByRole('complementary')).toBeInTheDocument();
    expect(screen.getByText(/Waypoint for/i)).toBeInTheDocument();
  });

  it('allows dismissing banner and stores in sessionStorage', () => {
    render(<MobileAppBanner forceShow={true} />);

    const closeBtn = screen.getByLabelText(/Dismiss banner/i);
    fireEvent.click(closeBtn);

    expect(sessionStorage.getItem('waypoint_app_banner_dismissed')).toBe('true');
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });

  it('opens guide modal when Guide button is clicked', () => {
    render(<MobileAppBanner forceShow={true} />);

    const guideBtn = screen.getByText('Guide');
    fireEvent.click(guideBtn);

    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
