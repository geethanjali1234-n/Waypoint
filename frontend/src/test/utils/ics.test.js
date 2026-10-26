import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { downloadIcs } from '../../utils/ics';

describe('downloadIcs', () => {
  let createObjectURLSpy;
  let revokeObjectURLSpy;
  let clickSpy;

  beforeEach(() => {
    createObjectURLSpy = vi.fn(() => 'blob:mock-url');
    revokeObjectURLSpy = vi.fn();
    URL.createObjectURL = createObjectURLSpy;
    URL.revokeObjectURL = revokeObjectURLSpy;
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const baseEvent = {
    id: 'event-1',
    title: 'Team Sync',
    startDateTime: '2026-03-05T09:00:00',
    endDateTime: '2026-03-05T10:00:00',
    eventMode: 'IN_PERSON',
    venueName: 'HQ',
    address: '123 Main St',
  };

  it('creates a downloadable blob and triggers a click', () => {
    downloadIcs(baseEvent);

    expect(createObjectURLSpy).toHaveBeenCalledTimes(1);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url');
  });

  it('names the downloaded file after the sanitized event title', () => {
    let capturedAnchor;
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = originalCreateElement(tag);
      if (tag === 'a') capturedAnchor = el;
      return el;
    });

    downloadIcs({ ...baseEvent, title: 'Q1 Kickoff!! 2026' });

    expect(capturedAnchor.download).toBe('Q1_Kickoff___2026.ics');
  });

  it('builds an ICS blob containing the event summary and UID', () => {
    let blobContent;
    globalThis.Blob = class extends Blob {
      constructor(parts, options) {
        super(parts, options);
        blobContent = parts.join('');
      }
    };

    downloadIcs(baseEvent);

    expect(blobContent).toContain('SUMMARY:Team Sync');
    expect(blobContent).toContain('UID:event-1@waypoint');
    expect(blobContent).toContain('LOCATION:HQ, 123 Main St');
  });

  it('uses the meeting link as location for online events', () => {
    let blobContent;
    globalThis.Blob = class extends Blob {
      constructor(parts, options) {
        super(parts, options);
        blobContent = parts.join('');
      }
    };

    downloadIcs({
      ...baseEvent,
      eventMode: 'ONLINE',
      meetingLink: 'https://meet.example.com/abc',
    });

    expect(blobContent).toContain('LOCATION:https://meet.example.com/abc');
  });

  it('falls back to the start time as end time when endDateTime is missing', () => {
    let blobContent;
    globalThis.Blob = class extends Blob {
      constructor(parts, options) {
        super(parts, options);
        blobContent = parts.join('');
      }
    };

    const eventWithoutEnd = { ...baseEvent };
    delete eventWithoutEnd.endDateTime;
    downloadIcs(eventWithoutEnd);

    const dtstart = blobContent.match(/DTSTART:(\d{8}T\d{6})/)[1];
    const dtend = blobContent.match(/DTEND:(\d{8}T\d{6})/)[1];
    expect(dtend).toBe(dtstart);
  });

  it('escapes newlines in the description', () => {
    let blobContent;
    globalThis.Blob = class extends Blob {
      constructor(parts, options) {
        super(parts, options);
        blobContent = parts.join('');
      }
    };

    downloadIcs({ ...baseEvent, description: 'Line one\nLine two' });

    expect(blobContent).toContain('DESCRIPTION:Line one\\nLine two');
  });
});
