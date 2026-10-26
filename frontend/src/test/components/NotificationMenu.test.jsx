import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import NotificationMenu from '../../components/navigation/NotificationMenu';

describe('NotificationMenu', () => {
  it('announces the full unread count while keeping the trigger name stable', () => {
    render(<NotificationMenu unreadCount={123} />);
    expect(screen.getByRole('button', { name: 'Notifications' })).toHaveAccessibleDescription('123 unread notifications');
    expect(screen.getByText('99+')).toHaveAttribute('aria-hidden', 'true');
  });
  it('omits the count when the endpoint has not supplied one', () => {
    render(<NotificationMenu unreadCount={null} />);
    expect(screen.getByRole('button', { name: 'Notifications' })).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
  it('refreshes when opened and only shows the empty state after a successful empty load', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<NotificationMenu notifications={[]} onOpen={onOpen} />);

    await user.click(screen.getByRole('button', { name: 'Notifications' }));

    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/No notifications yet/)).toBeInTheDocument();
  });

  it('shows a retry action instead of pretending the list is empty when loading fails', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(
      <NotificationMenu
        notifications={[]}
        error="Notifications could not be loaded. Try again."
        onRetry={onRetry}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Notifications' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Notifications could not be loaded.');
    expect(screen.queryByText(/No notifications yet/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
