import { describe, expect, it } from 'vitest';
import { queryKnowledgeBase } from '../../components/chatbot/knowledgeBase';

const paths = (result) => (result.actions || []).map((action) => action.path);

describe('local knowledge base typo matching', () => {
  it('matches a primary keyword with a one-character typo (Levenshtein distance 1)', () => {
    const correct = queryKnowledgeBase('overtime', 'EMPLOYEE');
    const typo = queryKnowledgeBase('overtme', 'EMPLOYEE');
    expect(typo.text).toBe(correct.text);
    expect(paths(typo)).toEqual(paths(correct));
  });

  it('answers the original "adrreess" typo exactly like address', () => {
    expect(queryKnowledgeBase('adrreess', 'EMPLOYEE').text).toBe(queryKnowledgeBase('address', 'EMPLOYEE').text);
    const correct = queryKnowledgeBase('I need to change address', 'EMPLOYEE');
    const typo = queryKnowledgeBase('I need to change adrreess', 'EMPLOYEE');
    expect(typo.text).toBe(correct.text);
    expect(paths(typo)).toEqual(paths(correct));
    expect(typo.text).toMatch(/Personal Details/);
  });
});
