import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import JobSeekerContactUsPage from '../../pages/JobSeekerContactUsPage';

describe('JobSeekerContactUsPage', () => {
  it('renders mock support contact details', () => {
    render(
      <MemoryRouter>
        <JobSeekerContactUsPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: 'Contact support' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /support@waypoint\.example/i })).toHaveAttribute('href', 'mailto:support@waypoint.example');
    expect(screen.getByRole('link', { name: /\(313\) 555-0148/i })).toHaveAttribute('href', 'tel:+13135550148');
    expect(screen.getByText('9:00 AM to 5:00 PM ET')).toBeInTheDocument();
    expect(screen.getByText('Detroit, MI 48226')).toBeInTheDocument();
  });
});
