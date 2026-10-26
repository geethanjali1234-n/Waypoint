import { describe, it, expect, vi, beforeEach } from 'vitest';
import weatherApi from '../../api/weatherApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('weatherApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getEventForecast calls GET /events/:id/weather', () => {
    weatherApi.getEventForecast('event-1');

    expect(apiClient.get).toHaveBeenCalledWith('/events/event-1/weather');
  });
});
