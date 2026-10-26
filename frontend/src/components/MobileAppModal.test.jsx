import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MobileAppModal from './MobileAppModal';

describe('MobileAppModal', () => {
  it('does not render when isOpen is false', () => {
    const { container } = render(
      <MobileAppModal isOpen={false} onClose={() => {}} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders modal with tabs when isOpen is true', () => {
    render(
      <MobileAppModal isOpen={true} onClose={() => {}} defaultPlatform="android" />
    );

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Get Waypoint Mobile App/i)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Android/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /iOS/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Scan QR/i })).toBeInTheDocument();
  });

  it('renders Android APK download link in Android tab', () => {
    render(
      <MobileAppModal isOpen={true} onClose={() => {}} defaultPlatform="android" />
    );

    const downloadLink = screen.getByRole('link', { name: /Download Android App/i });
    expect(downloadLink).toBeInTheDocument();
    expect(downloadLink).toHaveAttribute('href', expect.stringContaining('/downloads/waypoint.apk'));
    expect(downloadLink).toHaveAttribute('download', 'waypoint.apk');
  });

  it('switches to iOS tab and displays iOS installation guide', () => {
    render(
      <MobileAppModal isOpen={true} onClose={() => {}} defaultPlatform="android" />
    );

    const iosTab = screen.getByRole('tab', { name: /iOS/i });
    fireEvent.click(iosTab);

    expect(screen.getByText(/Waypoint for iPhone & iPad/i)).toBeInTheDocument();
    expect(screen.getByText(/Add to Home Screen/i)).toBeInTheDocument();
  });

  it('calls onClose when close button is clicked', () => {
    const handleClose = vi.fn();
    render(<MobileAppModal isOpen={true} onClose={handleClose} />);

    const closeBtn = screen.getByLabelText(/Close download modal/i);
    fireEvent.click(closeBtn);
    expect(handleClose).toHaveBeenCalledTimes(1);
  });
});
