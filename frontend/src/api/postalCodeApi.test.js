import axios from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import postalCodeApi from './postalCodeApi';

vi.mock('axios', () => ({
  default: { get: vi.fn() },
}));

describe('postalCodeApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('returns sorted unique postal codes for a city', async () => {
    axios.get.mockResolvedValue({
      data: {
        places: [
          { 'post code': '78702' },
          { 'post code': '78701' },
          { 'post code': '78702' },
        ],
      },
    });

    await expect(postalCodeApi.getPostalCodes('TX', 'Austin'))
      .resolves.toEqual(['78701', '78702']);
  });

  it('uses the postal-service city alias when needed', async () => {
    axios.get.mockResolvedValue({ data: { places: [{ 'post code': '63101' }] } });

    await postalCodeApi.getPostalCodes('MO', 'St. Louis');

    expect(axios.get).toHaveBeenCalledWith(
      'https://api.zippopotam.us/US/MO/Saint%20Louis',
    );
  });

  it('uses curated city ZIPs when the postal API has no city-name result', async () => {
    await expect(postalCodeApi.getPostalCodes('MN', 'Bloomington'))
      .resolves.toContain('55420');
    expect(axios.get).not.toHaveBeenCalled();
  });
});
