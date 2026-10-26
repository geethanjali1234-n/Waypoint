import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import EmployeeManagementPage from '../../../pages/myhr/features/employeeManagement/EmployeeManagementPage';
import { searchAddressSuggestions } from '../../../api/addressAutocompleteApi';
import myHrAccountApi from '../../../api/myHrAccountApi';

vi.mock('../../../api/addressAutocompleteApi');
vi.mock('../../../api/myHrAccountApi');

const suggestion = {
  id: 'place-1', displayAddress: '500 Congress Ave, Austin, TX 78701',
  streetAddress: '500 Congress Ave', city: 'Austin', state: 'TX', zipCode: '78701',
};

async function openForm() {
  render(<MemoryRouter><EmployeeManagementPage role="COMPANY_ADMIN" /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: 'Create Account' }));
  return screen.getByRole('dialog', { name: 'Create Account' });
}

async function finishDebounce() {
  await act(async () => { await vi.advanceTimersByTimeAsync(400); });
}

describe('EmployeeManagementPage address autocomplete', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    searchAddressSuggestions.mockResolvedValue([]);
    myHrAccountApi.getEligibleReportingManagers.mockResolvedValue({ data: { data: [] } });
  });

  afterEach(() => vi.useRealTimers());

  it('debounces lookup and ignores street text shorter than three characters', async () => {
    const dialog = await openForm();
    const street = within(dialog).getByLabelText(/Street Address/);
    fireEvent.change(street, { target: { value: '12' } });
    await finishDebounce();
    expect(searchAddressSuggestions).not.toHaveBeenCalled();

    fireEvent.change(street, { target: { value: '500 Congress' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(399); });
    expect(searchAddressSuggestions).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(searchAddressSuggestions).toHaveBeenCalledTimes(1);
  });

  it('renders suggestions, autofills all address fields, closes, and remains editable', async () => {
    searchAddressSuggestions.mockResolvedValue([suggestion]);
    const dialog = await openForm();
    fireEvent.change(within(dialog).getByLabelText(/Street Address/), { target: { value: '500 Cong' } });
    await finishDebounce();

    fireEvent.click(within(dialog).getByRole('button', { name: suggestion.displayAddress }));
    expect(within(dialog).getByLabelText(/Street Address/)).toHaveValue('500 Congress Ave');
    expect(within(dialog).getByLabelText(/^City/)).toHaveValue('Austin');
    expect(within(dialog).getByLabelText(/^State/)).toHaveValue('TX');
    expect(within(dialog).getByLabelText(/ZIP Code/)).toHaveValue('78701');
    expect(within(dialog).queryByRole('list', { name: 'Address suggestions' })).not.toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/^City/), { target: { value: 'Round Rock' } });
    expect(within(dialog).getByLabelText(/^City/)).toHaveValue('Round Rock');
  });

  it('clears suggestions when Street Address is cleared', async () => {
    searchAddressSuggestions.mockResolvedValue([suggestion]);
    const dialog = await openForm();
    const street = within(dialog).getByLabelText(/Street Address/);
    fireEvent.change(street, { target: { value: '500 Cong' } });
    await finishDebounce();
    expect(within(dialog).getByRole('list', { name: 'Address suggestions' })).toBeInTheDocument();

    fireEvent.change(street, { target: { value: '' } });
    expect(within(dialog).queryByRole('list', { name: 'Address suggestions' })).not.toBeInTheDocument();
  });

  it('does not let an older lookup replace newer suggestions', async () => {
    let resolveOlder;
    const older = new Promise((resolve) => { resolveOlder = resolve; });
    searchAddressSuggestions.mockReturnValueOnce(older).mockResolvedValueOnce([suggestion]);
    const dialog = await openForm();
    const street = within(dialog).getByLabelText(/Street Address/);

    fireEvent.change(street, { target: { value: '500 Main' } });
    await finishDebounce();
    fireEvent.change(street, { target: { value: '500 Congress' } });
    await finishDebounce();
    expect(within(dialog).getByText(suggestion.displayAddress)).toBeInTheDocument();

    await act(async () => { resolveOlder([{ ...suggestion, id: 'old', displayAddress: 'Old result' }]); });
    expect(within(dialog).queryByText('Old result')).not.toBeInTheDocument();
    expect(within(dialog).getByText(suggestion.displayAddress)).toBeInTheDocument();
  });

  it('keeps manual entry usable after API failure or no results', async () => {
    searchAddressSuggestions.mockRejectedValueOnce(new Error('controlled'));
    const dialog = await openForm();
    const street = within(dialog).getByLabelText(/Street Address/);
    fireEvent.change(street, { target: { value: 'Unusual rural address' } });
    await finishDebounce();
    expect(within(dialog).getByText(/enter the address manually/i)).toBeInTheDocument();
    expect(street).toHaveValue('Unusual rural address');

    searchAddressSuggestions.mockResolvedValueOnce([]);
    fireEvent.change(street, { target: { value: 'Another manual address' } });
    await finishDebounce();
    fireEvent.change(within(dialog).getByLabelText(/^City/), { target: { value: 'Austin' } });
    expect(within(dialog).getByLabelText(/^City/)).toHaveValue('Austin');
    expect(within(dialog).queryByRole('list', { name: 'Address suggestions' })).not.toBeInTheDocument();
  });
});
