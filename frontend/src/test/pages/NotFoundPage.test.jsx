import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import NotFoundPage from '../../pages/NotFoundPage';

describe('NotFoundPage', () => {
  it('renders the 404 message', () => {
    render(<NotFoundPage />);

    expect(screen.getByText('404 - Page Not Found')).toBeInTheDocument();
  });
});
