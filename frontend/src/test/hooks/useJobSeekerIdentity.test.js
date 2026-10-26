import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import useJobSeekerIdentity from '../../hooks/useJobSeekerIdentity';
import jobSeekerProfileApi from '../../api/jobSeekerProfileApi';
import resolveFileUrl from '../../api/resolveFileUrl';

vi.mock('../../api/jobSeekerProfileApi');
vi.mock('../../api/resolveFileUrl');

describe('useJobSeekerIdentity', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns undefined firstName and profilePicturePath before the profile loads', () => {
    jobSeekerProfileApi.getProfile.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useJobSeekerIdentity());

    expect(result.current).toMatchObject({ firstName: undefined, profilePicturePath: undefined });
  });

  it('updates identity with the resolved firstName and profile picture path once loaded', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValue({
      data: { data: { firstName: 'Ada', profilePictureUrl: '/uploads/pic.png' } },
    });
    resolveFileUrl.mockReturnValue('http://localhost:8081/uploads/pic.png');

    const { result } = renderHook(() => useJobSeekerIdentity());

    await waitFor(() => {
      expect(result.current.firstName).toBe('Ada');
    });
    expect(resolveFileUrl).toHaveBeenCalledWith('/uploads/pic.png');
    expect(result.current.profilePicturePath).toBe('http://localhost:8081/uploads/pic.png');
  });

  it('keeps the default identity when the profile fails to load', async () => {
    jobSeekerProfileApi.getProfile.mockRejectedValue(new Error('network error'));

    const { result } = renderHook(() => useJobSeekerIdentity());

    await waitFor(() => {
      expect(jobSeekerProfileApi.getProfile).toHaveBeenCalled();
    });
    expect(result.current).toMatchObject({ firstName: undefined, profilePicturePath: undefined });
  });

  it('does not update state after unmount when the profile resolves late', async () => {
    let resolvePromise;
    jobSeekerProfileApi.getProfile.mockReturnValue(
      new Promise((resolve) => {
        resolvePromise = resolve;
      })
    );

    const { result, unmount } = renderHook(() => useJobSeekerIdentity());
    unmount();

    resolvePromise({ data: { data: { firstName: 'Ada', profilePictureUrl: null } } });

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(result.current).toMatchObject({ firstName: undefined, profilePicturePath: undefined });
  });

  it('re-fetches and updates identity when refresh() is called', async () => {
    jobSeekerProfileApi.getProfile.mockResolvedValueOnce({
      data: { data: { firstName: 'Ada', profilePictureUrl: null } },
    });
    resolveFileUrl.mockReturnValue(undefined);

    const { result } = renderHook(() => useJobSeekerIdentity());
    await waitFor(() => {
      expect(result.current.firstName).toBe('Ada');
    });

    jobSeekerProfileApi.getProfile.mockResolvedValueOnce({
      data: { data: { firstName: 'Ada', profilePictureUrl: '/uploads/new-pic.png' } },
    });
    resolveFileUrl.mockReturnValue('http://localhost:8081/uploads/new-pic.png');

    await result.current.refresh();

    await waitFor(() => {
      expect(result.current.profilePicturePath).toBe('http://localhost:8081/uploads/new-pic.png');
    });
    expect(jobSeekerProfileApi.getProfile).toHaveBeenCalledTimes(2);
  });
});
