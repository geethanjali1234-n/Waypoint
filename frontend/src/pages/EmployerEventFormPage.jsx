import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Card, DatePicker, FormLabel, ValidationMessage } from '../components';
import employerEventApi from '../api/employerEventApi';
import postalCodeApi from '../api/postalCodeApi';
import weatherApi from '../api/weatherApi';
import { STATE_DATA, STATE_OPTIONS } from './jobPostingOptions';
import './EmployerEventFormPage.css';

const EVENT_TYPES = [
  { label: 'Workshop', value: 'WORKSHOP' },
  { label: 'Webinar', value: 'WEBINAR' },
  { label: 'Job Fair', value: 'JOB_FAIR' },
  { label: 'Networking', value: 'NETWORKING' },
  { label: 'Info Session', value: 'INFO_SESSION' },
];

const EVENT_MODES = [
  { label: 'Online', value: 'ONLINE' },
  { label: 'Offline', value: 'OFFLINE' },
  { label: 'Hybrid', value: 'HYBRID' },
];

const INITIAL_FORM = {
  title: '',
  description: '',
  eventType: '',
  eventMode: '',
  venueName: '',
  streetAddress: '',
  city: '',
  state: '',
  zipCode: '',
  country: 'US',
  meetingLink: '',
  startDateTime: '',
  endDateTime: '',
  registrationDeadline: '',
  capacity: '',
};

// Same local-date convention as JobPostingPage's applicationDeadline field,
// so "today" lines up with the browser's local calendar, not UTC.
function getLocalDateString() {
  const now = new Date();
  const localDate = new Date(now.getTime() - now.getTimezoneOffset() * 60 * 1000);
  return localDate.toISOString().slice(0, 10);
}

function formatTimeLabel(hour24, minute) {
  const period = hour24 >= 12 ? 'PM' : 'AM';
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  return `${hour12}:${String(minute).padStart(2, '0')} ${period}`;
}

const TIME_OPTIONS = Array.from({ length: 48 }, (_, index) => {
  const hour = Math.floor(index / 2);
  const minute = index % 2 === 0 ? 0 : 30;
  const value = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  return { value, label: formatTimeLabel(hour, minute) };
});

// The backend expects a single LocalDateTime-compatible string per field
// (e.g. "2026-08-14T14:00"), matching what a native datetime-local input
// used to produce. These helpers let the UI use separate date/time controls
// while form.startDateTime / endDateTime / registrationDeadline keep holding
// that same combined string.
function splitDateTime(value) {
  if (!value) return { date: '', time: '' };
  const [date = '', time = ''] = value.split('T');
  return { date, time };
}

function combineDateTime(date, time) {
  if (!date && !time) return '';
  return `${date}T${time}`;
}

// The backend still only accepts a single free-form `address` string (plus
// `venueName` separately) - this combines the granular location fields into
// that exact shape, e.g. "123 Main Street, Austin, TX 78701, US".
function combineAddress({ streetAddress, city, state, zipCode, country }) {
  const parts = [];
  if (streetAddress.trim()) parts.push(streetAddress.trim());
  if (city.trim()) parts.push(city.trim());

  const stateZip = [state.trim(), zipCode.trim()].filter(Boolean).join(' ');
  if (stateZip) parts.push(stateZip);

  if (country.trim()) parts.push(country.trim());

  return parts.join(', ');
}

function normalizeState(value = '') {
  const normalizedValue = value.trim().toLowerCase();
  const state = STATE_OPTIONS.find((option) => (
    option.value.toLowerCase() === normalizedValue
      || option.abbreviation.toLowerCase() === normalizedValue
  ));
  return state?.value || value;
}

function splitAddress(address) {
  const [streetAddress = '', city = '', stateZip = '', country = 'US'] = (address || '')
    .split(',')
    .map((part) => part.trim());
  const match = stateZip.match(/^(.+?)\s+(\S+)$/);
  return {
    streetAddress,
    city,
    state: normalizeState(match?.[1] || stateZip),
    zipCode: match?.[2] || '',
    country: country || 'US',
  };
}

function RequiredMark() {
  return <span className="wp-employer-event-form__required" aria-hidden="true">*</span>;
}

function formatForecastTime(value) {
  if (!value) return '';
  const parsed = new Date(value.replace(' ', 'T'));
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function TimeSelect({ name, label, value, onChange, error }) {
  return (
    <div className="wp-field">
      <FormLabel htmlFor={name} required>
        {label}
      </FormLabel>
      <select
        id={name}
        name={name}
        value={value}
        onChange={onChange}
        className={'wp-field__control' + (error ? ' wp-field__control--error' : '')}
      >
        <option value="">Select time</option>
        {TIME_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ValidationMessage message={error} variant="error" />
    </div>
  );
}

export default function EmployerEventFormPage({ isModal = false, onCancel, onSaved }) {
  const navigate = useNavigate();
  const { eventId } = useParams();
  const isEditing = Boolean(eventId);
  const today = getLocalDateString();

  const [eventCode, setEventCode] = useState('');
  const [form, setForm] = useState(INITIAL_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [formError, setFormError] = useState('');
  const [isPublishing, setIsPublishing] = useState(false);
  const [isLoadingEvent, setIsLoadingEvent] = useState(isEditing);
  const [zipCodeOptions, setZipCodeOptions] = useState([]);
  const [zipCodeError, setZipCodeError] = useState('');
  const [isLoadingZipCodes, setIsLoadingZipCodes] = useState(false);

  const [weather, setWeather] = useState(null);
  const [weatherError, setWeatherError] = useState('');
  const [isCheckingWeather, setIsCheckingWeather] = useState(false);

  useEffect(() => {
    if (isEditing) {
      employerEventApi
        .getEmployerEvent(eventId)
        .then((response) => {
          const savedEvent = response.data.data;
          setEventCode(savedEvent.eventCode);
          setForm({
            ...INITIAL_FORM,
            ...splitAddress(savedEvent.address),
            title: savedEvent.title || '',
            description: savedEvent.description || '',
            eventType: savedEvent.eventType || '',
            eventMode: savedEvent.eventMode || '',
            venueName: savedEvent.venueName || '',
            meetingLink: savedEvent.meetingLink || '',
            startDateTime: savedEvent.startDateTime || '',
            endDateTime: savedEvent.endDateTime || '',
            registrationDeadline: savedEvent.registrationDeadline || '',
            capacity: savedEvent.capacity == null ? '' : String(savedEvent.capacity),
          });
        })
        .catch((error) => {
          setFormError(error?.response?.data?.message || 'Unable to load this event.');
        })
        .finally(() => setIsLoadingEvent(false));
      return;
    }

    employerEventApi
      .getNewEventCode()
      .then((response) => setEventCode(response.data.data))
      .catch(() => {
        /* Event code is display-only; creation still works without it. */
      });
  }, [eventId, isEditing]);

  useEffect(() => {
    const selectedState = STATE_DATA.find((state) => (
      state.value.toLowerCase() === form.state.toLowerCase()
        || state.abbreviation.toLowerCase() === form.state.toLowerCase()
    ));

    if (!selectedState || !form.city) {
      setZipCodeOptions([]);
      setZipCodeError('');
      setIsLoadingZipCodes(false);
      return undefined;
    }

    let isCurrentRequest = true;
    setIsLoadingZipCodes(true);
    setZipCodeError('');

    postalCodeApi.getPostalCodes(selectedState.abbreviation, form.city)
      .then((postalCodes) => {
        if (!isCurrentRequest) return;
        setZipCodeOptions(postalCodes);
        setZipCodeError(postalCodes.length ? '' : 'No ZIP codes were found for this city.');
      })
      .catch(() => {
        if (!isCurrentRequest) return;
        setZipCodeOptions([]);
        setZipCodeError('Unable to load ZIP codes. Select the city again to retry.');
      })
      .finally(() => {
        if (isCurrentRequest) setIsLoadingZipCodes(false);
      });

    return () => {
      isCurrentRequest = false;
    };
  }, [form.city, form.state]);

  const closeForm = () => {
    if (isModal) {
      onCancel?.();
      return;
    }
    navigate('/employer/events');
  };

  useEffect(() => {
    if (!isModal) return undefined;

    const handleEscape = (event) => {
      if (event.key === 'Escape' && !isPublishing) onCancel?.();
    };

    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [isModal, isPublishing, onCancel]);

  const clearWeatherResult = () => {
    setWeather(null);
    setWeatherError('');
  };

  const updateField = (event) => {
    const { name, value } = event.target;
    setForm((current) => {
      if (name === 'state') return { ...current, state: value, city: '', zipCode: '' };
      if (name === 'city') return { ...current, city: value, zipCode: '' };
      return { ...current, [name]: value };
    });
    setFieldErrors((current) => (name === 'state'
      ? { ...current, state: undefined, city: undefined, zipCode: undefined }
      : name === 'city'
        ? { ...current, city: undefined, zipCode: undefined }
      : { ...current, [name]: undefined }));
    setFormError('');

    if (name === 'city' || name === 'state' || name === 'country') {
      clearWeatherResult();
    }
    if (name === 'eventMode' && value === 'ONLINE') {
      clearWeatherResult();
    }
  };

  const updateDatePart = (fieldName, dateValue) => {
    const { time } = splitDateTime(form[fieldName]);
    setForm((current) => ({ ...current, [fieldName]: combineDateTime(dateValue, time) }));
    setFieldErrors((current) => ({ ...current, [fieldName]: undefined }));
    setFormError('');

    if (fieldName === 'startDateTime') clearWeatherResult();
  };

  const updateTimePart = (fieldName, timeValue) => {
    const { date } = splitDateTime(form[fieldName]);
    setForm((current) => ({ ...current, [fieldName]: combineDateTime(date, timeValue) }));
    setFieldErrors((current) => ({ ...current, [fieldName]: undefined }));
    setFormError('');

    if (fieldName === 'startDateTime') clearWeatherResult();
  };

  // A single error message is kept per logical field (matching the existing
  // fieldErrors shape), then attributed to whichever sub-control it concerns
  // so it renders exactly once instead of under both the date and the time.
  const rowErrors = (fieldName) => {
    const message = fieldErrors[fieldName];
    if (!message) return { dateError: undefined, timeError: undefined };
    const { date } = splitDateTime(form[fieldName]);
    return date ? { dateError: undefined, timeError: message } : { dateError: message, timeError: undefined };
  };

  const checkWeather = async () => {
    if (!form.city.trim()) {
      setWeatherError('Enter the event city before checking the weather.');
      return;
    }

    if (!form.state.trim()) {
      setWeatherError('Enter the event state before checking the weather.');
      return;
    }

    const { date, time } = splitDateTime(form.startDateTime);
    if (!date || !time) {
      setWeatherError('Select the event start date and time before checking the weather.');
      return;
    }

    setIsCheckingWeather(true);
    setWeatherError('');
    setWeather(null);

    try {
      const response = await weatherApi.getForecast({
        city: form.city.trim(),
        state: form.state.trim(),
        country: form.country.trim() || 'US',
        eventDateTime: form.startDateTime,
      });
      setWeather(response.data.data);
    } catch (error) {
      setWeatherError(
        error?.response?.data?.message || 'Unable to retrieve the weather forecast. Please try again.'
      );
    } finally {
      setIsCheckingWeather(false);
    }
  };

  const validate = () => {
    const errors = {};

    if (!form.title.trim()) errors.title = 'Event title is required';
    if (!form.description.trim()) errors.description = 'Description is required';
    if (!form.eventType) errors.eventType = 'Event type is required';
    if (!form.eventMode) errors.eventMode = 'Event mode is required';
    if (!form.capacity) errors.capacity = 'Capacity is required';
    else if (Number(form.capacity) <= 0) errors.capacity = 'Capacity must be greater than zero';

    const start = splitDateTime(form.startDateTime);
    const end = splitDateTime(form.endDateTime);
    const deadline = splitDateTime(form.registrationDeadline);

    if (!start.date) errors.startDateTime = 'Start date is required';
    else if (!start.time) errors.startDateTime = 'Start time is required';

    if (!end.date) errors.endDateTime = 'End date is required';
    else if (!end.time) errors.endDateTime = 'End time is required';

    if (!deadline.date) errors.registrationDeadline = 'Registration deadline date is required';
    else if (!deadline.time) errors.registrationDeadline = 'Registration deadline time is required';

    if (!errors.startDateTime && !errors.endDateTime) {
      if (new Date(form.endDateTime) <= new Date(form.startDateTime)) {
        errors.endDateTime = 'End date and time must be after the start date and time';
      }
    }

    if (!errors.registrationDeadline && !errors.startDateTime) {
      if (new Date(form.registrationDeadline) >= new Date(form.startDateTime)) {
        errors.registrationDeadline = 'Registration deadline must be before the event starts';
      }
    }

    if (form.eventMode === 'ONLINE' && !form.meetingLink.trim()) {
      errors.meetingLink = 'Meeting link is required for online events';
    }

    if (form.eventMode === 'OFFLINE' || form.eventMode === 'HYBRID') {
      if (!form.venueName.trim()) errors.venueName = 'Venue name is required';
      if (!form.streetAddress.trim()) errors.streetAddress = 'Street address is required';
      if (!form.city.trim()) errors.city = 'City is required';
      if (!form.state.trim()) errors.state = 'State is required';
      if (!form.zipCode.trim()) errors.zipCode = 'ZIP code is required';
      else if (!zipCodeOptions.includes(form.zipCode)) {
        errors.zipCode = 'Select a valid ZIP code for the chosen city';
      }
      if (!form.country.trim()) errors.country = 'Country is required';
    }

    if (form.eventMode === 'HYBRID' && !form.meetingLink.trim()) {
      errors.meetingLink = 'Meeting link is required for hybrid events';
    }

    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const saveEvent = async (event) => {
    event.preventDefault();
    if (!validate()) return;

    const payload = {
      title: form.title.trim(),
      description: form.description.trim(),
      eventType: form.eventType,
      eventMode: form.eventMode,
      venueName: form.venueName.trim() || null,
      address: showVenueFields ? combineAddress(form) || null : null,
      meetingLink: form.meetingLink.trim() || null,
      startDateTime: form.startDateTime,
      endDateTime: form.endDateTime,
      registrationDeadline: form.registrationDeadline,
      capacity: Number(form.capacity),
    };

    setIsPublishing(true);
    setFormError('');
    try {
      const response = isEditing
        ? await employerEventApi.updateEvent(eventId, payload)
        : await employerEventApi.createEvent(payload);

      if (isModal) {
        onSaved?.(response?.data?.data);
      } else {
        navigate('/employer/events');
      }
    } catch (error) {
      const responseBody = error?.response?.data;
      if (responseBody?.data && typeof responseBody.data === 'object') {
        setFieldErrors(responseBody.data);
      }
      setFormError(responseBody?.message || `Unable to ${isEditing ? 'update' : 'create'} this event.`);
    } finally {
      setIsPublishing(false);
    }
  };

  const showVenueFields = form.eventMode === 'OFFLINE' || form.eventMode === 'HYBRID';
  const showMeetingLinkField = form.eventMode === 'ONLINE' || form.eventMode === 'HYBRID';
  const selectedState = STATE_DATA.find((state) => (
    state.value.toLowerCase() === form.state.toLowerCase()
      || state.abbreviation.toLowerCase() === form.state.toLowerCase()
  ));
  const cityOptions = selectedState?.cities || [];

  const startDate = splitDateTime(form.startDateTime).date;
  const startErrors = rowErrors('startDateTime');
  const endErrors = rowErrors('endDateTime');
  const deadlineErrors = rowErrors('registrationDeadline');

  const formCard = (
    <Card className="wp-employer-event-form__card">
      <div className="wp-employer-event-form__heading">
        <div>
          <h1 id={isModal ? 'employer-event-form-title' : undefined}>
            {isEditing ? 'Edit event' : 'New event'}
          </h1>
          <p>Event ID: {eventCode || 'Generating…'}</p>
        </div>
        <Button variant="secondary" onClick={closeForm}>
          Cancel
        </Button>
      </div>

      {formError && <ValidationMessage message={formError} variant="error" />}

      {isLoadingEvent ? <p className="wp-employer-event-form__loading">Loading event…</p> : (
      <form onSubmit={saveEvent} noValidate className="wp-employer-event-form__grid">
        <label className="wp-employer-event-form__wide">
          <span>Title <RequiredMark /></span>
          <input
            name="title"
            value={form.title}
            onChange={updateField}
            className={fieldErrors.title ? 'is-error' : ''}
          />
          {fieldErrors.title && <small>{fieldErrors.title}</small>}
        </label>

        <label className="wp-employer-event-form__wide">
          <span>Description <RequiredMark /></span>
          <textarea
            name="description"
            rows="4"
            value={form.description}
            onChange={updateField}
            className={fieldErrors.description ? 'is-error' : ''}
          />
          {fieldErrors.description && <small>{fieldErrors.description}</small>}
        </label>

        <label>
          <span>Event type <RequiredMark /></span>
          <select
            name="eventType"
            value={form.eventType}
            onChange={updateField}
            className={fieldErrors.eventType ? 'is-error' : ''}
          >
            <option value="">Select one</option>
            {EVENT_TYPES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          {fieldErrors.eventType && <small>{fieldErrors.eventType}</small>}
        </label>

        <label>
          <span>Event mode <RequiredMark /></span>
          <select
            name="eventMode"
            value={form.eventMode}
            onChange={updateField}
            className={fieldErrors.eventMode ? 'is-error' : ''}
          >
            <option value="">Select one</option>
            {EVENT_MODES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          {fieldErrors.eventMode && <small>{fieldErrors.eventMode}</small>}
        </label>

        {showMeetingLinkField && (
          <label className="wp-employer-event-form__wide">
            <span>Meeting link <RequiredMark /></span>
            <input
              name="meetingLink"
              value={form.meetingLink}
              onChange={updateField}
              className={fieldErrors.meetingLink ? 'is-error' : ''}
            />
            {fieldErrors.meetingLink && <small>{fieldErrors.meetingLink}</small>}
          </label>
        )}

        {showVenueFields && (
          <>
            <h2 className="wp-employer-event-form__section-heading wp-employer-event-form__wide">
              Event location
            </h2>

            <label className="wp-employer-event-form__wide">
              <span>Venue name <RequiredMark /></span>
              <input
                name="venueName"
                value={form.venueName}
                onChange={updateField}
                className={fieldErrors.venueName ? 'is-error' : ''}
              />
              {fieldErrors.venueName && <small>{fieldErrors.venueName}</small>}
            </label>

            <label className="wp-employer-event-form__wide">
              <span>Street address <RequiredMark /></span>
              <input
                name="streetAddress"
                value={form.streetAddress}
                onChange={updateField}
                className={fieldErrors.streetAddress ? 'is-error' : ''}
              />
              {fieldErrors.streetAddress && <small>{fieldErrors.streetAddress}</small>}
            </label>

            <label>
              <span>State <RequiredMark /></span>
              <select
                name="state"
                value={form.state}
                onChange={updateField}
                className={fieldErrors.state ? 'is-error' : ''}
              >
                <option value="">Select state</option>
                {STATE_OPTIONS.map((state) => (
                  <option key={state.value} value={state.value}>{state.label}</option>
                ))}
              </select>
              {fieldErrors.state && <small>{fieldErrors.state}</small>}
            </label>

            <label>
              <span>City <RequiredMark /></span>
              <select
                name="city"
                value={form.city}
                onChange={updateField}
                disabled={!selectedState}
                className={fieldErrors.city ? 'is-error' : ''}
              >
                <option value="">
                  {selectedState ? 'Select city' : 'Select state first'}
                </option>
                {cityOptions.map((city) => (
                  <option key={city} value={city}>{city}</option>
                ))}
              </select>
              {fieldErrors.city && <small>{fieldErrors.city}</small>}
            </label>

            <label>
              <span>ZIP code <RequiredMark /></span>
              <select
                name="zipCode"
                value={form.zipCode}
                onChange={updateField}
                disabled={!form.city || isLoadingZipCodes || Boolean(zipCodeError)}
                className={fieldErrors.zipCode ? 'is-error' : ''}
              >
                <option value="">
                  {!form.city
                    ? 'Select city first'
                    : isLoadingZipCodes
                      ? 'Loading ZIP codes…'
                      : zipCodeError
                        ? 'ZIP codes unavailable'
                        : 'Select ZIP code'}
                </option>
                {zipCodeOptions.map((zipCode) => (
                  <option key={zipCode} value={zipCode}>{zipCode}</option>
                ))}
              </select>
              {zipCodeError && <small>{zipCodeError}</small>}
              {fieldErrors.zipCode && <small>{fieldErrors.zipCode}</small>}
            </label>

            <label>
              <span>Country <RequiredMark /></span>
              <input
                name="country"
                value={form.country}
                onChange={updateField}
                className={fieldErrors.country ? 'is-error' : ''}
              />
              {fieldErrors.country && <small>{fieldErrors.country}</small>}
            </label>
          </>
        )}

        <DatePicker
          name="startDate"
          label="Start date"
          required
          min={today}
          value={startDate}
          onChange={(event) => updateDatePart('startDateTime', event.target.value)}
          error={startErrors.dateError}
        />
        <TimeSelect
          name="startTime"
          label="Start time"
          value={splitDateTime(form.startDateTime).time}
          onChange={(event) => updateTimePart('startDateTime', event.target.value)}
          error={startErrors.timeError}
        />

        <DatePicker
          name="endDate"
          label="End date"
          required
          min={startDate || today}
          value={splitDateTime(form.endDateTime).date}
          onChange={(event) => updateDatePart('endDateTime', event.target.value)}
          error={endErrors.dateError}
        />
        <TimeSelect
          name="endTime"
          label="End time"
          value={splitDateTime(form.endDateTime).time}
          onChange={(event) => updateTimePart('endDateTime', event.target.value)}
          error={endErrors.timeError}
        />

        <DatePicker
          name="registrationDeadlineDate"
          label="Registration deadline date"
          required
          min={today}
          max={startDate || undefined}
          value={splitDateTime(form.registrationDeadline).date}
          onChange={(event) => updateDatePart('registrationDeadline', event.target.value)}
          error={deadlineErrors.dateError}
        />
        <TimeSelect
          name="registrationDeadlineTime"
          label="Registration deadline time"
          value={splitDateTime(form.registrationDeadline).time}
          onChange={(event) => updateTimePart('registrationDeadline', event.target.value)}
          error={deadlineErrors.timeError}
        />

        <label className="wp-employer-event-form__wide">
          <span>Capacity <RequiredMark /></span>
          <input
            name="capacity"
            type="number"
            min="1"
            required
            value={form.capacity}
            onChange={updateField}
            className={fieldErrors.capacity ? 'is-error' : ''}
          />
          {fieldErrors.capacity && <small>{fieldErrors.capacity}</small>}
        </label>

        {showVenueFields && (
          <div className="wp-employer-event-form__weather wp-employer-event-form__wide">
            <h2>Weather forecast</h2>

            {!weather && (
              <p className="wp-employer-event-form__weather-hint">
                Check the forecast for the selected event location and start time.
              </p>
            )}

            <Button type="button" variant="secondary" onClick={checkWeather} loading={isCheckingWeather}>
              {isCheckingWeather ? 'Checking forecast…' : 'Check weather'}
            </Button>

            {weatherError && <ValidationMessage message={weatherError} variant="error" />}

            {weather && (
              <div className="wp-employer-event-form__weather-result">
                <p className="wp-employer-event-form__weather-result-title">
                  {weather.city}, {weather.country}
                </p>
                <p className="wp-employer-event-form__weather-result-condition">
                  {weather.weather} — {weather.description}
                </p>
                <dl className="wp-employer-event-form__weather-result-grid">
                  <div>
                    <dt>Temperature</dt>
                    <dd>{Math.round(weather.temperature)}°F</dd>
                  </div>
                  <div>
                    <dt>Feels like</dt>
                    <dd>{Math.round(weather.feelsLike)}°F</dd>
                  </div>
                  <div>
                    <dt>Humidity</dt>
                    <dd>{weather.humidity}%</dd>
                  </div>
                  <div>
                    <dt>Wind speed</dt>
                    <dd>{Math.round(weather.windSpeed)} mph</dd>
                  </div>
                  <div>
                    <dt>Rain chance</dt>
                    <dd>{Math.round(weather.rainChance)}%</dd>
                  </div>
                </dl>
                <p className="wp-employer-event-form__weather-result-time">
                  Forecast for {formatForecastTime(weather.forecastTime)}
                </p>
              </div>
            )}
          </div>
        )}

        <div className="wp-employer-event-form__actions wp-employer-event-form__wide">
          <Button type="button" variant="secondary" onClick={closeForm}>
            Cancel
          </Button>
          <Button type="submit" loading={isPublishing}>
            {isEditing ? 'Save changes' : 'Publish event'}
          </Button>
        </div>
      </form>
      )}
      </Card>
  );

  if (isModal) {
    return (
      <div
        className="wp-employer-event-form__modal-backdrop"
        onMouseDown={(event) => {
          if (event.target === event.currentTarget && !isPublishing) closeForm();
        }}
      >
        <div
          className="wp-employer-event-form wp-employer-event-form--modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="employer-event-form-title"
          onMouseDown={(event) => event.stopPropagation()}
        >
          {formCard}
        </div>
      </div>
    );
  }

  return <div className="wp-employer-event-form">{formCard}</div>;
}
