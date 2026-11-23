import { describe, expect, it } from 'vitest';
import {
  canonicalizeWaviQuestion, classifyOwnRecordIntent, classifyUnsupportedField, correctWaviWord, interpretWaviQuestion,
  isAmbiguousStatusQuestion, normalizeWaviQuestion,
} from '../../components/chatbot/waviIntents';
import table from '../fixtures/waviIntentCases.json';

// The same interpretation cases the backend's MyHrQuestionInterpreterTest checks.
describe('Wavi natural-language interpretation (shared with the backend)', () => {
  it('has the shared interpretation table', () => {
    expect(table.interpretationCases.length).toBeGreaterThanOrEqual(50);
  });

  it.each(table.interpretationCases.map((item) => [item.question, item]))('interprets "%s"', (question, expected) => {
    const interpreted = interpretWaviQuestion(question, expected.earlier ?? []);
    const routed = interpreted ?? question;
    if ('canonical' in expected) expect(interpreted ?? canonicalizeWaviQuestion(question)).toBe(expected.canonical);
    if ('unsupported' in expected) expect(classifyUnsupportedField(routed)).toBe(expected.unsupported);
    if ('clarify' in expected) expect(isAmbiguousStatusQuestion(question)).toBe(true);
    if ('kind' in expected) {
      const intent = classifyOwnRecordIntent(routed);
      if (expected.kind === null) {
        expect(intent).toBeNull();
        return;
      }
      expect(intent).toEqual({ kind: expected.kind, subject: expected.subject, aspect: expected.aspect, field: expected.field ?? null });
    }
  });

  it('corrects only near misspellings of Waypoint vocabulary', () => {
    expect(['timesheat', 'aproved', 'approvd', 'staus', 'profle', 'manger', 'documnet', 'employe', 'pnding'].map(correctWaviWord))
      .toEqual(['timesheet', 'approved', 'approved', 'status', 'profile', 'manager', 'document', 'employee', 'pending']);
    for (const word of ['writing', 'wanting', 'states', 'later', 'changed', 'employer', 'employed', 'employees', 'approval', 'polite', 'policy']) {
      expect(correctWaviWord(word)).toBe(word);
    }
  });
});

// The same case table the backend's MyHrOwnRecordIntentTest checks, so the shortcut under an answer is chosen
// by the same rules as the route that produced the answer.
describe('Wavi own-record intents (shared with the backend)', () => {
  it('has the shared case table', () => {
    expect(table.cases.length).toBeGreaterThanOrEqual(60);
  });

  it.each(table.cases.map((item) => [item.question, item]))('classifies "%s"', (question, expected) => {
    const intent = classifyOwnRecordIntent(question);
    if (expected.kind === null) {
      expect(intent).toBeNull();
      return;
    }
    expect(intent).toEqual({
      kind: expected.kind,
      subject: expected.subject,
      aspect: expected.aspect,
      field: expected.field ?? null,
    });
  });

  it('normalizes polite and addressing prefixes the same way as the backend', () => {
    const plain = normalizeWaviQuestion('What is the status of my profile change request?');
    for (const polite of ['Can you please tell me what is the status of my profile change request?',
      'Hey Wavi, what is the status of my profile change request?',
      'Please tell me what is the status of my profile change request']) {
      expect(normalizeWaviQuestion(polite)).toBe(plain);
    }
  });
});
