import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  PageHeader,
  Card,
  Loader,
  EmptyState,
  ValidationMessage,
  SearchInput,
  Dropdown,
  FilterBar,
  Button,
  Pagination,
  TwoColumnLayout,
  Chip,
  Toast,
} from '../../components';
import useGeolocation from '../../hooks/useGeolocation';
import jobApi from '../../api/jobApi';
import formatLocationWithoutZip from '../../utils/formatLocation';
import SalaryFilterDropdown from './components/SalaryFilterDropdown';
import { DISTANCE_OPTIONS } from '../../constants/distanceOptions';
import {
  EMPLOYMENT_TYPE_OPTIONS,
  WORK_MODE_OPTIONS,
  DATE_POSTED_OPTIONS,
  resolvePostedAfterDate,
} from './constants/jobFilterOptions';
import './CareersPage.css';

const PAGE_SIZE = 10;
const SALARY_SLIDER_MIN = 10000;
const SALARY_SLIDER_MAX = 1000000;
const SALARY_SLIDER_STEP = 10000;
const DEFAULT_SALARY_RANGE = [SALARY_SLIDER_MIN, SALARY_SLIDER_MAX];

function formatSalaryValue(value) {
  return `$${Math.round(value / 1000)}K`;
}

function formatShortDate(value) {
  if (!value) return null;

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) return null;

  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export default function CareersPage() {
  const navigate = useNavigate();

  // ── Search inputs (not applied until Search is clicked) ───────────────────
  const [title, setTitle] = useState('');
  const [location, setLocation] = useState('');

  // ── Active filters (applied to the API query) ─────────────────────────────
  const [appliedTitle, setAppliedTitle] = useState('');
  const [appliedLocation, setAppliedLocation] = useState('');
  const [appliedSalaryRange, setAppliedSalaryRange] = useState(DEFAULT_SALARY_RANGE);
  const [datePosted, setDatePosted] = useState('');
  const [employmentType, setEmploymentType] = useState('');
  const [workMode, setWorkMode] = useState('');
  const [distance, setDistance] = useState('');
  const [page, setPage] = useState(0);

  const [jobs, setJobs] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  const [selectedJobId, setSelectedJobId] = useState(null);
  const [selectedJobDetail, setSelectedJobDetail] = useState(null);
  const [isDetailLoading, setIsDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(null);

  // ── Geolocation (default closest-first sort + explicit distance filter) ───
  const { coords: userCoords, requestLocation } = useGeolocation();
  const [toast, setToast] = useState(null); // { message, variant }

  const showToast = (message, variant = 'info') => {
    setToast({ message, variant });
    setTimeout(() => setToast(null), 4000);
  };

  // ── Request geolocation automatically on page load, silently ──────────────
  // Enables "closest to me first" as the default sort even before the
  // job seeker touches any filter. A denial/unsupported browser here just
  // means the list stays in its normal date order - no error shown, since
  // the job seeker never explicitly asked for this.
  useEffect(() => {
    requestLocation().catch(() => {
      /* Silent - default date-ordered results are still correct without coords. */
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Request geolocation when the job seeker explicitly picks a distance
  // value ─── Usually a no-op by this point since the effect above already
  // resolved (or failed) location on mount. Skipped entirely when a city is
  // already applied - the backend centers the radius on the city in that
  // case, so GPS isn't needed (and shouldn't prompt for permission it won't
  // use).
  useEffect(() => {
    if (!distance || distance === 'any') return;
    if (appliedLocation.trim() !== '') return;
    if (userCoords) return; // already have it
    requestLocation().catch((error) => {
      const message = error.message === 'UNSUPPORTED'
        ? 'Your browser does not support geolocation.'
        : 'Location access denied — distance filter cannot be applied.';
      showToast(message, 'error');
      setDistance('');
    });
  }, [distance, userCoords, requestLocation, appliedLocation]);

  // ── Fetch job list whenever any applied filter changes ────────────────────
  // lat/lng are sent whenever known so the backend can sort closest-first by
  // default, even without a distance filter selected; maxDistanceMiles is
  // only sent once the job seeker picks a real distance value, which turns
  // that into a hard radius filter (city, when applied, wins as the center).
  const fetchJobs = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    const params = { page, size: PAGE_SIZE };
    if (appliedTitle.trim() !== '') params.title = appliedTitle.trim();
    if (appliedLocation.trim() !== '') params.location = appliedLocation.trim();
    if (employmentType !== '') params.employmentType = employmentType;
    if (workMode !== '') params.workMode = workMode;
    if (appliedSalaryRange[0] > SALARY_SLIDER_MIN) params.salaryMin = appliedSalaryRange[0];
    if (appliedSalaryRange[1] < SALARY_SLIDER_MAX) params.salaryMax = appliedSalaryRange[1];
    const resolvedPostedAfter = resolvePostedAfterDate(datePosted);
    if (resolvedPostedAfter) params.postedAfter = resolvedPostedAfter;
    if (userCoords) {
      params.lat = userCoords.lat;
      params.lng = userCoords.lng;
    }
    if (distance && distance !== 'any') {
      params.maxDistanceMiles = distance;
    }

    try {
      const response = await jobApi.listJobs(params);
      const data = response.data.data;
      setJobs(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
      setSelectedJobId(data.content.length > 0 ? data.content[0].id : null);
    } catch (fetchError) {
      const message = fetchError?.response?.data?.message || 'Unable to load jobs. Please try again.';
      setError(message);
      setJobs([]);
      setSelectedJobId(null);
    } finally {
      setIsLoading(false);
    }
  }, [
    page, appliedTitle, appliedLocation, appliedSalaryRange, employmentType, workMode, datePosted,
    distance, userCoords,
  ]);

  useEffect(() => {
    fetchJobs();
  }, [fetchJobs]);

  useEffect(() => {
    if (!selectedJobId) {
      setSelectedJobDetail(null);
      setDetailError(null);
      return;
    }

    let isCancelled = false;

    setIsDetailLoading(true);
    setDetailError(null);

    jobApi
      .getJobById(selectedJobId)
      .then((response) => {
        if (isCancelled) return;
        setSelectedJobDetail(response.data.data);
      })
      .catch((fetchError) => {
        if (isCancelled) return;
        const message = fetchError?.response?.data?.message || 'Unable to load this job. Please try again.';
        setDetailError(message);
        setSelectedJobDetail(null);
      })
      .finally(() => {
        if (isCancelled) return;
        setIsDetailLoading(false);
      });

    return () => {
      isCancelled = true;
    };
  }, [selectedJobId]);

  const handleSearch = (event) => {
    event.preventDefault();
    setPage(0);
    setAppliedTitle(title);
    setAppliedLocation(location);
  };

  const handleClearAll = () => {
    setTitle('');
    setLocation('');
    setAppliedTitle('');
    setAppliedLocation('');
    setAppliedSalaryRange(DEFAULT_SALARY_RANGE);
    setDatePosted('');
    setEmploymentType('');
    setWorkMode('');
    setDistance('');
    setPage(0);
  };

  const handleSalaryApply = (range) => {
    setPage(0);
    setAppliedSalaryRange(range);
  };

  const handleSalaryReset = () => {
    setPage(0);
    setAppliedSalaryRange(DEFAULT_SALARY_RANGE);
  };

  const handlePageChange = (uiPage) => {
    setPage(uiPage - 1);
  };

  const handleSelectJob = (jobId) => {
    if (jobId === selectedJobId) return;
    setSelectedJobId(jobId);
  };

  const handleApply = () => {
    if (!selectedJobId || selectedJobDetail?.hasApplied) return;
    navigate(`/careers/${selectedJobId}/apply`);
  };

  const jobListPanel = (
    <div className="wp-careers-list">
      <p className="wp-careers-group-label">Jobs</p>
      {jobs.map((job) => (
        <button
          type="button"
          key={job.id}
          aria-pressed={job.id === selectedJobId}
          className={`wp-careers-list__item ${
            job.id === selectedJobId ? 'wp-careers-list__item--selected' : ''
          }`}
          onClick={() => handleSelectJob(job.id)}
        >
          <p className="wp-careers-list__title">{job.title}</p>
          <p className="wp-careers-list__subtitle">{formatLocationWithoutZip(job.location)}</p>
        </button>
      ))}
    </div>
  );

  const detailPanel = (
    <div className="wp-careers-detail">
      {isDetailLoading && <Loader label="Loading job details…" />}

      {!isDetailLoading && detailError && <ValidationMessage message={detailError} variant="error" />}

      {!isDetailLoading && !detailError && !selectedJobDetail && (
        <EmptyState
          title="No jobs are available yet."
          description="Jobs posted by employers will appear here."
        />
      )}

      {!isDetailLoading && !detailError && selectedJobDetail && (
        <Card className="wp-careers-detail-card">
          <div className="wp-careers-detail-head">
            <div>
              {selectedJobDetail.jobCode && (
                <p className="wp-careers-job-id-tag">{selectedJobDetail.jobCode}</p>
              )}
              <h2 className="wp-careers-detail-title">{selectedJobDetail.title}</h2>
              {selectedJobDetail.companyName && (
                <p className="wp-careers-detail-company">{selectedJobDetail.companyName}</p>
              )}
            </div>
            <Button variant="primary" onClick={handleApply} disabled={selectedJobDetail.hasApplied}>
              {selectedJobDetail.hasApplied ? 'Applied' : 'Apply now'}
            </Button>
          </div>

          <div className="wp-careers-facts-grid">
            <div>
              <p className="wp-careers-facts-k">Location</p>
              <p className="wp-careers-facts-v">{formatLocationWithoutZip(selectedJobDetail.location)}</p>
            </div>
            <div>
              <p className="wp-careers-facts-k">Work type</p>
              <p className="wp-careers-facts-v">{selectedJobDetail.workMode}</p>
            </div>
            <div>
              <p className="wp-careers-facts-k">Employment type</p>
              <p className="wp-careers-facts-v">{selectedJobDetail.employmentType}</p>
            </div>
            {(selectedJobDetail.salaryMin || selectedJobDetail.salaryMax) && (
              <div>
                <p className="wp-careers-facts-k">Salary</p>
                <p className="wp-careers-facts-v">
                  ${selectedJobDetail.salaryMin ?? '—'} – ${selectedJobDetail.salaryMax ?? '—'}
                </p>
              </div>
            )}
            {formatShortDate(selectedJobDetail.applicationDeadline) && (
              <div>
                <p className="wp-careers-facts-k">Apply by</p>
                <p className="wp-careers-facts-v">{formatShortDate(selectedJobDetail.applicationDeadline)}</p>
              </div>
            )}
          </div>

          <p className="wp-careers-section-label">About the role</p>
          <p className="wp-careers-body-copy">{selectedJobDetail.description}</p>

          {selectedJobDetail.requiredSkills && selectedJobDetail.requiredSkills.length > 0 && (
            <>
              <p className="wp-careers-section-label">Required skills</p>
              <div className="wp-careers-skill-tags">
                {selectedJobDetail.requiredSkills.map((skill) => (
                  <Chip key={skill} label={skill} />
                ))}
              </div>
            </>
          )}
        </Card>
      )}
    </div>
  );

  return (
    <div className="wp-careers-page">
      <div className="wp-careers-page__content">
        <PageHeader eyebrow="Careers" title="Find your next role" subtitle="Search jobs matched to your skills" />

        <form className="wp-careers-searchbar" onSubmit={handleSearch}>
          <SearchInput
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Job title or role, e.g. Frontend engineer"
          />
          <div className="wp-careers-searchbar__loc">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M12 21s7-6.6 7-12a7 7 0 1 0-14 0c0 5.4 7 12 7 12z" />
              <circle cx="12" cy="9" r="2.5" />
            </svg>
            <SearchInput
              value={location}
              onChange={(event) => setLocation(event.target.value)}
              placeholder="City, State"
            />
          </div>
          <Button type="submit" variant="primary">
            Search
          </Button>
        </form>

        <FilterBar>
          <Dropdown
            name="datePosted"
            options={DATE_POSTED_OPTIONS}
            value={datePosted}
            onChange={(event) => setDatePosted(event.target.value)}
            placeholder="Date posted"
            className="wp-careers-filter-dropdown"
          />
          <Dropdown
            name="employmentType"
            options={EMPLOYMENT_TYPE_OPTIONS}
            value={employmentType}
            onChange={(event) => setEmploymentType(event.target.value)}
            placeholder="Employment type"
            className="wp-careers-filter-dropdown"
          />
          <Dropdown
            name="workMode"
            options={WORK_MODE_OPTIONS}
            value={workMode}
            onChange={(event) => setWorkMode(event.target.value)}
            placeholder="Work type"
            className="wp-careers-filter-dropdown"
          />
          <SalaryFilterDropdown
            label="Salary"
            min={SALARY_SLIDER_MIN}
            max={SALARY_SLIDER_MAX}
            step={SALARY_SLIDER_STEP}
            value={appliedSalaryRange}
            onApply={handleSalaryApply}
            onReset={handleSalaryReset}
            formatValue={formatSalaryValue}
          />
          <Dropdown
            name="distance"
            options={DISTANCE_OPTIONS}
            value={distance}
            onChange={(event) => setDistance(event.target.value)}
            placeholder="Distance"
            className="wp-careers-filter-dropdown"
          />
          <button type="button" className="wp-careers-filters__clear-all" onClick={handleClearAll}>
            Clear all
          </button>
        </FilterBar>

        {isLoading && <Loader label="Loading jobs…" />}

        {!isLoading && error && <ValidationMessage message={error} variant="error" />}

        {!isLoading && !error && (
          <div className="wp-careers-results-row">
            <p className="wp-careers-eyebrow">Open positions</p>
            <span className="wp-careers-results-count">
              {totalElements} result{totalElements === 1 ? '' : 's'}
            </span>
          </div>
        )}

        {!isLoading && !error && (
          <TwoColumnLayout className="wp-careers-split" main={jobListPanel} side={detailPanel} />
        )}

        {!isLoading && !error && jobs.length > 0 && totalPages > 1 && (
          <Pagination
            currentPage={page + 1}
            totalPages={totalPages}
            onPageChange={handlePageChange}
          />
        )}
      </div>

      {toast && (
        <div className="wp-careers-page__toast-container">
          <Toast
            message={toast.message}
            variant={toast.variant}
            onClose={() => setToast(null)}
          />
        </div>
      )}
    </div>
  );
}
