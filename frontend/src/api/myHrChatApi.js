import apiClient from './apiClient';

const MAX_STAMP_LENGTH = 200;

function stamp(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= MAX_STAMP_LENGTH ? value : null;
}

/**
 * The server's opaque stamps for this turn's question and answer. They are returned unchanged as conversation
 * history; the browser never interprets them, and the server decides what they permit.
 */
function historyProvenance(value) {
  const user = stamp(value?.user);
  const assistant = stamp(value?.assistant);
  return user && assistant ? { user, assistant } : null;
}

/**
 * Resolves to { message, knowledgeStatus, citations, historyProvenance, actions }. knowledgeStatus and citations are absent
 * unless the answer was grounded in the approved MyHR knowledge base; historyProvenance is null when not issued.
 */
export async function sendMyHrChatMessage(message, history = []) {
  const response = await apiClient.post('/myhr/chat', { message, history }, { timeout: 100000 });
  if (response.data?.success === false) throw new Error('Chat request was not successful');
  const data = response.data?.data;
  return {
    message: data?.message,
    knowledgeStatus: data?.knowledgeStatus ?? null,
    citations: Array.isArray(data?.citations) ? data.citations : [],
    historyProvenance: historyProvenance(data?.historyProvenance),
    // Page keys the server chose for this answer; null when it leaves the shortcut to the chat UI.
    actions: Array.isArray(data?.actions) ? data.actions.filter((key) => typeof key === 'string') : null,
  };
}
