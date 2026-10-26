import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import DashboardPage from '../../pages/DashboardPage';

describe('DashboardPage', () => {
  it('renders the blank development area', () => {
    render(<DashboardPage />);

    expect(screen.getByLabelText('Development area')).toBeInTheDocument();
  });
});
