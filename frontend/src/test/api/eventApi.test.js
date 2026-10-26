import { describe, it, expect, vi, beforeEach } from 'vitest';
import eventApi from '../../api/eventApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('eventApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getEvents calls GET /events with no params when none are given', () => {
    eventApi.getEvents();

    expect(apiClient.get).toHaveBeenCalledWith('/events', { params: {} });
  });

  it('getEvents only forwards recognized, truthy filter params', () => {
    eventApi.getEvents({
      keyword: 'career',
      dateFilter: 'UPCOMING',
      eventType: 'WORKSHOP',
      eventMode: 'ONLINE',
      unknownParam: 'ignored',
    });

    expect(apiClient.get).toHaveBeenCalledWith('/events', {
      params: {
        keyword: 'career',
        dateFilter: 'UPCOMING',
        eventType: 'WORKSHOP',
        eventMode: 'ONLINE',
      },
    });
  });

  it('getEvents omits falsy filter params', () => {
    eventApi.getEvents({ keyword: '', eventType: null });

    expect(apiClient.get).toHaveBeenCalledWith('/events', { params: {} });
  });

  it('getEvents forwards lat/lng/maxDistanceMiles when given', () => {
    eventApi.getEvents({ location: 'Irving', lat: 32.8140, lng: -96.9489, maxDistanceMiles: '50' });

    expect(apiClient.get).toHaveBeenCalledWith('/events', {
      params: {
        location: 'Irving',
        lat: 32.8140,
        lng: -96.9489,
        maxDistanceMiles: '50',
      },
    });
  });

  it('getEvents omits lat/lng/maxDistanceMiles when not given', () => {
    eventApi.getEvents({ keyword: 'career' });

    expect(apiClient.get).toHaveBeenCalledWith('/events', { params: { keyword: 'career' } });
  });

  it('getEventById calls GET /events/:id', () => {
    eventApi.getEventById('event-1');

    expect(apiClient.get).toHaveBeenCalledWith('/events/event-1');
  });

  it('registerForEvent calls POST /events/:id/register', () => {
    eventApi.registerForEvent('event-1');

    expect(apiClient.post).toHaveBeenCalledWith('/events/event-1/register');
  });

  it('cancelRegistration calls POST /events/:id/cancel-registration', () => {
    eventApi.cancelRegistration('event-1');

    expect(apiClient.post).toHaveBeenCalledWith('/events/event-1/cancel-registration');
  });
});
