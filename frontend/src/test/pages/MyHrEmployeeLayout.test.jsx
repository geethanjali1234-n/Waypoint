import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MyHrEmployeeLayout from '../../pages/MyHrEmployeeLayout';

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/employee/dashboard']}>
      <Routes>
        <Route path="/employee" element={<MyHrEmployeeLayout />}>
          <Route path="dashboard" element={<div>Employee dashboard</div>} />
          <Route path="profile" element={<div>Employee profile</div>} />
          <Route path="timesheets" element={<div>Employee timesheets</div>} />
          <Route path="paystubs" element={<div>Employee paystubs</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('MyHrEmployeeLayout', () => {
  it('places the notification beside the employee controls without a middle toggle or sidebar', () => {
    renderLayout();

    expect(screen.queryByRole('navigation', { name: 'MY HR employee navigation' })).not.toBeInTheDocument();
    const notificationButton = screen.getByRole('button', { name: 'Notifications' });
    expect(notificationButton).toBeInTheDocument();
    expect(notificationButton.closest('.myhr-employee-shell__header-actions')).not.toBeNull();
    expect(document.querySelector('.myhr-employee-shell__notification-toggle')).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });

  it('keeps notifications in the header instead of feature navigation', () => {
    renderLayout();

    expect(screen.queryByRole('link', { name: 'Notifications' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'My Profile' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument();
  });

  it('does not show My Profile in the employee profile menu', async () => {
    const user = userEvent.setup();
    renderLayout();

    await user.click(document.querySelector('.myhr-employee-shell__profile-menu .wp-profile-menu__trigger'));

    expect(screen.queryByRole('menuitem', { name: 'My Profile' })).not.toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Change Password' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Logout' })).toBeInTheDocument();
  });

  it('routes a notification to its related feature and closes the menu', async () => {
    const user = userEvent.setup();
    renderLayout();

    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    await user.click(screen.getByRole('button', { name: /Timesheet submission/ }));

    expect(screen.getByText('Employee timesheets')).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
