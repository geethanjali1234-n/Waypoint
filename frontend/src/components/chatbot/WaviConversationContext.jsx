import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
  getMyHrSession,
  getAccessToken,
  MY_HR_AUTHENTICATION_CLEARED_EVENT,
  MY_HR_SESSION_CHANGED_EVENT,
} from '../../auth/myHrSession';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { WAVI_OUT_OF_SCOPE_REPLY } from './waviScope';
import { chatErrorMessage } from './waviAnswerFormat';

const WaviConversationContext = createContext(null);
const EMPTY_CONVERSATION = {
  messages: [], inputValue: '', isTyping: false, feedback: '', hasUnread: false, isOpen: false,
};
let previousAccessToken = null;
let loginGeneration = 0;

export function waviSessionKey(session) {
  const principal = session?.userId ?? session?.loginId;
  const token = getAccessToken();
  if (token !== previousAccessToken) {
    previousAccessToken = token;
    loginGeneration += 1;
  }
  // A partial or malformed session is not a stable owner for personal chat history.
  return principal && token ? [principal, session.organizationId ?? '', session.role ?? '', loginGeneration].join('|') : null;
}

/**
 * In-memory, per-authenticated-session conversation state. It intentionally has no browser-storage
 * backing: messages can include employee-specific answers and must disappear at logout or account switch.
 */
export function WaviConversationProvider({ children }) {
  const sessionKeyRef = useRef(waviSessionKey(getMyHrSession()));
  const requestRef = useRef(0);
  const messageIdRef = useRef(1);
  const replyTimerRef = useRef(null);
  const successTimerRef = useRef(null);
  const openRef = useRef(false);
  // Synchronous: a second Enter or click can arrive before React re-renders with isTyping, and a second request
  // would also invalidate the first one's answer.
  const inFlightRef = useRef(false);
  const [sessionKey, setSessionKey] = useState(sessionKeyRef.current);
  const [conversation, setConversation] = useState(EMPTY_CONVERSATION);

  const cancelPending = useCallback(() => {
    clearTimeout(replyTimerRef.current);
    clearTimeout(successTimerRef.current);
    requestRef.current += 1;
    inFlightRef.current = false;
  }, []);

  const refreshAuthenticatedSession = useCallback(() => {
    const nextKey = waviSessionKey(getMyHrSession());
    if (sessionKeyRef.current === nextKey) return;
    sessionKeyRef.current = nextKey;
    cancelPending();
    setSessionKey(nextKey);
    // Keep the panel open if the user was already reading it, but remove every message and shortcut.
    // This makes an account transition visibly clean without retaining another account's content.
    setConversation((previous) => ({ ...EMPTY_CONVERSATION, isOpen: previous.isOpen }));
  }, [cancelPending]);

  useEffect(() => {
    const onStorage = (event) => {
      if (!event.key || event.key === 'myHrAuthenticatedUser' || event.key === 'accessToken') {
        refreshAuthenticatedSession();
      }
    };
    window.addEventListener('storage', onStorage);
    window.addEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, refreshAuthenticatedSession);
    window.addEventListener(MY_HR_SESSION_CHANGED_EVENT, refreshAuthenticatedSession);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener(MY_HR_AUTHENTICATION_CLEARED_EVENT, refreshAuthenticatedSession);
      window.removeEventListener(MY_HR_SESSION_CHANGED_EVENT, refreshAuthenticatedSession);
      cancelPending();
    };
  }, [cancelPending, refreshAuthenticatedSession]);

  const setChatOpen = useCallback((open) => {
    openRef.current = open;
    setConversation((previous) => ({ ...previous, isOpen: open, hasUnread: open ? false : previous.hasUnread }));
  }, []);

  const setInputValue = useCallback((inputValue) => {
    setConversation((previous) => ({ ...previous, inputValue }));
  }, []);

  const clearConversation = useCallback(() => {
    cancelPending();
    setConversation((previous) => ({ ...EMPTY_CONVERSATION, isOpen: previous.isOpen }));
  }, [cancelPending]);

  const sendMessage = useCallback(async (query, actions, normalizeReply) => {
    const normalizedQuery = query?.trim();
    if (!normalizedQuery) return;

    const askedBy = waviSessionKey(getMyHrSession());
    if (askedBy !== sessionKeyRef.current) {
      refreshAuthenticatedSession();
      return;
    }
    // One question at a time per conversation: a duplicate submission is ignored, never sent.
    if (inFlightRef.current) return;
    inFlightRef.current = true;

    const requestId = ++requestRef.current;
    const userMessage = {
      id: ++messageIdRef.current,
      sender: 'user',
      text: normalizedQuery,
      timestamp: new Date().toISOString(),
      sessionKey: askedBy,
    };
    // Each message is returned with the stamp the server issued for it, if any. The server alone decides what a
    // stamp permits; a message without one is never sent on to an external AI provider.
    const history = conversation.messages
      .filter((message) => (message.sender === 'user' || message.sender === 'assistant') && !message.error)
      .slice(-10)
      .map((message) => (message.provenance
        ? { role: message.sender, content: message.text, provenance: message.provenance }
        : { role: message.sender, content: message.text }));

    setConversation((previous) => ({
      ...previous,
      messages: [...previous.messages, userMessage],
      inputValue: '',
      isTyping: true,
      feedback: '',
    }));
    clearTimeout(successTimerRef.current);

    try {
      const [reply] = await Promise.all([
        sendMyHrChatMessage(normalizedQuery, history),
        new Promise((resolve) => { replyTimerRef.current = setTimeout(resolve, 250); }),
      ]);
      // A session transition, a restart, or a newer request invalidates this answer before it can render.
      if (requestId !== requestRef.current || waviSessionKey(getMyHrSession()) !== askedBy) return;
      const normalized = normalizeReply(reply);
      const { text, citations, knowledgeStatus } = normalized;
      // actions: the shortcuts for this answer, or a function choosing them from the normalized reply.
      const shortcuts = typeof actions === 'function' ? actions(normalized) : actions;
      // Stamps cover the exact texts the server sent and received; anything shown differently goes back unstamped.
      const stamps = text && reply?.historyProvenance && text === reply.message ? reply.historyProvenance : null;
      setConversation((previous) => ({
        ...previous,
        messages: [...previous.messages.map((message) => (stamps && message.id === userMessage.id
          ? { ...message, provenance: stamps.user }
          : message)), {
          id: ++messageIdRef.current,
          sender: 'assistant',
          ...(stamps ? { provenance: stamps.assistant } : {}),
          text: text || 'The assistant returned an empty response. Please try again.',
          timestamp: new Date().toISOString(),
          sessionKey: askedBy,
          citations: text ? citations : [],
          knowledgeStatus,
          // A scope refusal never carries a shortcut, even if the question happened to mention a Waypoint page.
          actions: text === WAVI_OUT_OF_SCOPE_REPLY ? [] : (shortcuts ?? []),
        }],
        feedback: 'success',
      }));
      successTimerRef.current = setTimeout(() => {
        setConversation((previous) => ({ ...previous, feedback: '' }));
      }, 1800);
    } catch (error) {
      if (requestId !== requestRef.current || waviSessionKey(getMyHrSession()) !== askedBy) return;
      // A meaningful message from the HTTP status only; retry is offered only where sending again can help.
      const failure = chatErrorMessage(error);
      setConversation((previous) => ({
        ...previous,
        messages: [...previous.messages, {
          id: ++messageIdRef.current,
          sender: 'assistant',
          text: failure.text,
          timestamp: new Date().toISOString(),
          sessionKey: askedBy,
          actions: [],
          error: true,
          retryQuery: failure.retry ? normalizedQuery : null,
          failedQuestionId: userMessage.id,
        }],
        feedback: 'error',
      }));
    } finally {
      // Only the latest request releases the guard; a cancelled one was already released by cancelPending.
      if (requestId === requestRef.current) inFlightRef.current = false;
      if (requestId === requestRef.current && waviSessionKey(getMyHrSession()) === askedBy) {
        setConversation((previous) => ({
          ...previous,
          isTyping: false,
          hasUnread: openRef.current ? previous.hasUnread : true,
        }));
      }
    }
  }, [conversation.messages, refreshAuthenticatedSession]);

  /** Removes a failed answer and the question it failed for, so a retry shows the question once. */
  const dismissFailure = useCallback((errorMessageId) => {
    setConversation((previous) => {
      const failed = previous.messages.find((message) => message.id === errorMessageId && message.error);
      if (!failed) return previous;
      return {
        ...previous,
        messages: previous.messages.filter((message) => message.id !== errorMessageId
          && message.id !== failed.failedQuestionId),
        feedback: '',
      };
    });
  }, []);

  const value = {
    ...conversation,
    sessionKey,
    setMessages: (next) => setConversation((previous) => ({ ...previous,
      messages: typeof next === 'function' ? next(previous.messages) : next })),
    setIsOpen: (next) => setConversation((previous) => ({ ...previous,
      isOpen: typeof next === 'function' ? next(previous.isOpen) : next })),
    setIsTyping: (next) => setConversation((previous) => ({ ...previous,
      isTyping: typeof next === 'function' ? next(previous.isTyping) : next })),
    setHasUnread: (next) => setConversation((previous) => ({ ...previous,
      hasUnread: typeof next === 'function' ? next(previous.hasUnread) : next })),
    setFeedback: (next) => setConversation((previous) => ({ ...previous,
      feedback: typeof next === 'function' ? next(previous.feedback) : next })),
    setChatOpen,
    setInputValue,
    clearConversation,
    sendMessage,
    dismissFailure,
    refreshAuthenticatedSession,
  };
  return <WaviConversationContext.Provider value={value}>{children}</WaviConversationContext.Provider>;
}

export function useWaviConversation() {
  return useContext(WaviConversationContext);
}
