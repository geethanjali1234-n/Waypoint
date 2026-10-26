import { useCallback, useEffect, useRef, useState } from "react";
import jobSeekerProfileApi from "../api/jobSeekerProfileApi";
import resolveFileUrl from "../api/resolveFileUrl";

/**
 * Single shared source for the current job seeker's Navbar identity
 * (first name + resolved profile picture URL). Every job seeker page uses
 * this instead of independently fetching/resolving the picture, so the
 * Navbar always shows the same, up-to-date avatar everywhere.
 *
 * Returns a `refresh` function so pages that change the photo/name (e.g.
 * EditProfilePage) can update the shared JobSeekerLayout header immediately -
 * JobSeekerLayout only fetches on mount, and it stays mounted while
 * navigating within /job-seeker/*, so without this the header would show
 * stale data until the next full layout mount.
 */
export default function useJobSeekerIdentity() {
  const [identity, setIdentity] = useState({ firstName: undefined, profilePicturePath: undefined });
  const isMountedRef = useRef(true);

  const refresh = useCallback(() => {
    return jobSeekerProfileApi
      .getProfile()
      .then((response) => {
        if (!isMountedRef.current) return;
        const data = response.data.data;
        setIdentity({
          firstName: data.firstName,
          name: [data.firstName, data.lastName].filter(Boolean).join(' '),
          loginId: data.loginId || data.email,
          profilePicturePath: resolveFileUrl(data.profilePictureUrl),
        });
      })
      .catch(() => {
        // Navbar falls back to initials-only when identity can't be loaded.
      });
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    refresh();

    return () => {
      isMountedRef.current = false;
    };
  }, [refresh]);

  return { ...identity, refresh };
}
