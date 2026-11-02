import { useState, useRef, useEffect, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getMyHrSession } from '../../auth/myHrSession';
import { askWavi, permittedActions } from './waviAssistant';
import { resolveEffectiveRole } from '../../services/navigation/navigationRegistry';
import { getContextActions } from './quickActions';
import './WaypointChatbot.css';
import FloatingAssistant from './FloatingAssistant';
import StrokeIcon from '../global/StrokeIcon';
import AssistantMascot from './AssistantMascot';
import WaviMascot from './WaviMascot';
import useWaviMascot from './useWaviMascot';
import { isFollowUp } from './conversationIntents';
import wavyApi from '../../api/wavyApi';
import { getAccessToken } from '../../auth/myHrSession';

export default function WaypointChatbot({ displayName } = {}) {
  const navigate = useNavigate();
  const { animation: mascot, send: animateMascot } = useWaviMascot();
  const session = getMyHrSession();
  const location = useLocation();
  const role = resolveEffectiveRole(location.pathname, session);
  const suggestions = permittedActions(getContextActions(role, location.pathname + location.search), role);
  const [isOpen, setIsOpen] = useState(false);
  const [aiAvailable, setAiAvailable] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [messages, setMessages] = useState([]);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButtonRef = useRef(null);
  const menuRef = useRef(null);
  const feedRef = useRef(null);
  const hasConversation = messages.length > 0;
  const [hasUnread, setHasUnread] = useState(false);
  const openRef = useRef(false);
  const replyTimerRef = useRef(null);
  const requestRef = useRef(0);
  const lastTaskQueryRef = useRef('');
  const conversationContextRef = useRef(null);
  const successTimerRef = useRef(null);
  const [feedback, setFeedback] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const messageIdRef = useRef(1);
  const inputRef = useRef(null);

  useEffect(() => {
    if (!isOpen || !getAccessToken()) return;
    let active = true;
    wavyApi.capabilities().then((response) => {
      if (active) setAiAvailable(response.data?.data?.aiAvailable === true);
    }).catch(() => { if (active) setAiAvailable(false); });
    return () => { active = false; };
  }, [isOpen]);

  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 640px)');
    if (!media) return;
    const update = () => setIsMobile(media.matches);
    update();
    media.addEventListener?.('change', update);
    return () => media.removeEventListener?.('change', update);
  }, []);

  useEffect(() => {
    if (!isOpen || !isMobile) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, [isOpen, isMobile]);

  const toggleOpen = useCallback((open) => {
    openRef.current = open;
    setIsOpen(open);
    setMenuOpen(false);
    if (open) { setHasUnread(false); animateMascot({ type: 'OPEN_CHAT' }); }
  }, [animateMascot]);

  useEffect(() => () => {
    clearTimeout(replyTimerRef.current);
    clearTimeout(successTimerRef.current);
    requestRef.current += 1;
  }, []);

  // Auto-scroll to latest message
  useEffect(() => {
    if (isOpen && (messages.length > 0 || isTyping)) {
      const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      feedRef.current?.scrollTo?.({ top: feedRef.current.scrollHeight, behavior: reducedMotion ? 'auto' : 'smooth' });
    }
  }, [messages, isTyping, isOpen]);

  // Focus input when opened
  useEffect(() => {
    if (isOpen) {
      const timeout = setTimeout(() => {
        if (isMobile) document.querySelector('#waypoint-assistant-chat [aria-label="Close assistant"]')?.focus();
        else inputRef.current?.focus({ preventScroll: true });
      }, 150);
      const closeOnEscape = (event) => {
        if (isMobile && event.key === 'Tab') {
          const controls = [...document.querySelectorAll('#waypoint-assistant-chat button:not(:disabled), #waypoint-assistant-chat input')]
            .filter((element) => !element.closest('[hidden]'));
          const first = controls[0];
          const last = controls[controls.length - 1];
          if (event.shiftKey && event.target === first) { event.preventDefault(); last?.focus(); }
          if (!event.shiftKey && event.target === last) { event.preventDefault(); first?.focus(); }
        }
        if (event.key === 'Escape' && event.target.closest?.('#waypoint-assistant-chat')) {
          event.preventDefault();
          if (menuRef.current && !menuRef.current.hidden) {
            setMenuOpen(false);
            menuButtonRef.current?.focus();
          } else {
            toggleOpen(false);
          }
        }
      };
      document.addEventListener('keydown', closeOnEscape);
      return () => {
        clearTimeout(timeout);
        document.removeEventListener('keydown', closeOnEscape);
        document.querySelector('.wp-floating-assistant')?.focus({ preventScroll: true });
      };
    }
  }, [isOpen, isMobile, toggleOpen]);

  const handleSend = (textToSend) => {
    const query = (textToSend || inputValue).trim();
    if (!query || isTyping) return;

    const userMessage = {
      id: ++messageIdRef.current,
      sender: 'user',
      text: query,
      timestamp: new Date().toISOString(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInputValue('');
    setIsTyping(true);
    animateMascot({ type: 'REQUEST_SENT' });

    setFeedback('');
    clearTimeout(successTimerRef.current);
    const requestId = ++requestRef.current;
    const previousQuery = lastTaskQueryRef.current;
    replyTimerRef.current = setTimeout(async () => {
      let response;
      try {
        response = await askWavi(query, { role, route: location.pathname + location.search, previousQuery, context: conversationContextRef.current, history: messages.slice(-12) });
      } catch {
        response = { text: "I couldn't finish that request. Try again, or open the relevant workspace.", error: true, actions: [{ label: 'Try again', query }] };
      }
      if (requestId !== requestRef.current) return;
      if (response.mode === 'ai') setAiAvailable(true);
      else if (response.mode === 'limited') setAiAvailable(false);
      if (response.clearTask) lastTaskQueryRef.current = '';
      else if (!response.social && !response.preserveTask && !isFollowUp(query)) lastTaskQueryRef.current = query;
      if ('context' in response) conversationContextRef.current = response.context;
      else if (!response.social && !response.error) conversationContextRef.current = null;
      const botMessage = {
        id: ++messageIdRef.current,
        sender: 'assistant',
        text: response.text,
        timestamp: new Date().toISOString(),
        actions: response.actions || [],
        followUps: response.followUps || [],
        error: response.error,
        source: response.source,
        notice: response.notice && !messages.some((message) => message.notice === response.notice) ? response.notice : undefined,
      };
      setMessages((prev) => [...prev, botMessage]);
      setIsTyping(false);
      animateMascot({ type: 'RESPONSE_RECEIVED', error: response.error === true, needsClarification: response.needsClarification === true });
      if (response.actionCompleted === true) animateMascot({ type: 'TASK_CONFIRMED', kind: 'mutation', confirmed: response.confirmed === true });
      setFeedback(response.error ? 'error' : response.confirmed ? 'success' : '');
      successTimerRef.current = setTimeout(() => setFeedback(''), 1800);
      if (!openRef.current) setHasUnread(true);
    }, 250);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleActionClick = (action) => {
    if (action.query) { handleSend(action.query); return; }
    if (!action.path) return;
    navigate(action.path);
    toggleOpen(false);
  };

  const handleClearChat = () => {
    clearTimeout(replyTimerRef.current);
    clearTimeout(successTimerRef.current);
    requestRef.current += 1;
    lastTaskQueryRef.current = '';
    conversationContextRef.current = null;
    setFeedback('');
    setIsTyping(false);
    setMessages([]);
    animateMascot({ type: 'RESET' });
    setInputValue('');
    setHasUnread(false);
    setMenuOpen(false);
    inputRef.current?.focus();
  };

  // Helper to render markdown bolding (**text**) cleanly
  const renderFormattedText = (text) => {
    const parts = text.split(/(\*\*.*?\*\*)/g);
    return parts.map((part, index) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        return <strong key={index}>{part.slice(2, -2)}</strong>;
      }
      return part;
    });
  };

  return (
    <>
      {/* Floating Launcher Trigger */}
      <FloatingAssistant animation={mascot} onWake={() => animateMascot({ type: 'WAKE' })} hidden={isOpen} onClick={() => toggleOpen(!isOpen)}
        controls="waypoint-assistant-chat" expanded={isOpen} hasUnread={hasUnread} label="Open Wavy, Waypoint Assistant" title="Ask Wavy" />

      {/* Chat Window */}
      <div id="waypoint-assistant-chat" className={`wp-chatbot-window ${isOpen ? 'is-open' : ''} ${feedback ? `has-${feedback}` : ''}`}
          role="dialog" aria-label="Wavy, Waypoint Assistant Chat" aria-modal={isMobile && isOpen ? true : undefined} aria-hidden={!isOpen} inert={!isOpen}>
          {/* Header */}
          <div className="wp-chatbot-header">
            <div className="wp-chatbot-header__info">
              <div className="wp-chatbot-header__avatar" aria-hidden="true"><WaviMascot variant="compact" animation={mascot} active={isOpen} onWake={() => animateMascot({ type: 'WAKE' })} /></div>
              <div className="wp-chatbot-header__title">
                <strong>Wavy <span className="wp-chatbot-brand">by Waypoint</span></strong>
                <small><span className="wp-chatbot-online" /> {aiAvailable ? 'AI enabled · Read-only tools' : 'Workflow guide · Live lookups'}</small>
              </div>
            </div>
            <div className="wp-chatbot-header__actions">
              <div className="wp-chatbot-menu" onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget)) setMenuOpen(false);
              }}>
                <button ref={menuButtonRef} type="button" className="wp-chatbot-header__btn"
                  aria-label="Conversation options" aria-expanded={menuOpen} aria-controls="wp-chatbot-options"
                  onClick={() => setMenuOpen(!menuOpen)}><span className="wp-chatbot-menu-dots" aria-hidden="true"><i /><i /><i /></span></button>
                <div ref={menuRef} id="wp-chatbot-options" className="wp-chatbot-menu__popover" hidden={!menuOpen}>
                  <button type="button" onClick={handleClearChat}><StrokeIcon name="trash" size={16} /> Restart conversation</button>
                </div>
              </div>
              <button
                type="button"
                className="wp-chatbot-header__btn"
                onClick={() => toggleOpen(false)}
                title="Close assistant"
                aria-label="Close assistant"
              >
                <StrokeIcon name="close" />
              </button>
            </div>
          </div>

          {/* Quick Suggestions Carousel */}
          <div className="wp-chatbot-suggestions" aria-label="Suggested topics">
            {suggestions.slice(0, 2).map((sug, i) => (
              <button
                type="button"
                key={i}
                className="wp-chatbot-suggestion-chip"
                disabled={isTyping}
                onClick={() => handleSend(sug.query)}
              >
                {sug.label}
              </button>
            ))}
          </div>

          {/* Conversation Feed */}
          <div ref={feedRef} className="wp-chatbot-feed" role="log" aria-label="Conversation" aria-live="polite" aria-relevant="additions text">
            {!hasConversation && <div className="wp-chatbot-welcome">
              <AssistantMascot animation={mascot} active={isOpen} onGreet={() => animateMascot({ type: 'GREET' })} onWake={() => animateMascot({ type: 'WAKE' })} />
              <span>YOUR WORKDAY, A LITTLE EASIER</span>
              <h2>Hey! I'm Wavy, your Waypoint buddy <span aria-hidden="true">&#128075;</span></h2>
              <p>{displayName ? `${displayName}, let's make your workday a little easier.` : "A little guidance. A handy shortcut. Less busywork."}</p>
              <div className="wp-chatbot-welcome-cards">
                {suggestions.map((action) => <button type="button" key={action.label} onClick={() => handleSend(action.query)}>
                  <strong>{action.label}<span aria-hidden="true"><StrokeIcon name="arrow-right" size={14} /></span></strong>
                  <span>{action.description}</span>
                </button>)}
              </div>
              <p className="wp-chatbot-disclaimer">{aiAvailable ? 'AI replies can make mistakes. Check the cited sources. Wavy can read permitted information but cannot change HR records.' : 'Workflow guidance is available. AI conversation needs backend configuration. Live lookups are labeled with their source.'}</p>
            </div>}
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={`wp-chatbot-message wp-chatbot-message--${msg.sender} ${msg.error ? 'has-error' : ''} ${isOpen && msg.sender === 'assistant' && msg.id === messages[messages.length - 1].id ? 'is-explaining' : ''}`}
              >
                {msg.sender === 'assistant' && (
                  <div className="wp-chatbot-message__mascot" aria-hidden="true">
                    <WaviMascot animation={msg.id === messages[messages.length - 1].id ? mascot : { state: 'idle', paused: true }} active={isOpen} />
                  </div>
                )}
                <div className="wp-chatbot-bubble">
                  {msg.sender === 'assistant' && <span className="wp-chatbot-message__speaker">Wavy</span>}
                  {renderFormattedText(msg.text)}
                  {msg.notice && <span className="wp-chatbot-message__notice">{msg.notice}</span>}
                </div>

                {msg.source && <span className="wp-chatbot-message__source">Checked {new Date(msg.timestamp).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })} &middot; {msg.source}</span>}
                <time className="wp-chatbot-message__time" dateTime={msg.timestamp}>
                  {new Date(msg.timestamp).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                </time>
                {/* Optional Action Deep Links */}
                {msg.id === messages[messages.length - 1].id && msg.actions && msg.actions.length > 0 && (
                  <div className="wp-chatbot-actions">
                    {msg.actions.map((act, idx) => (
                      <button
                        type="button"
                        key={idx}
                        className="wp-chatbot-action-btn"
                        disabled={isTyping}
                        onClick={() => handleActionClick(act)}
                      >
                        <span>{act.label}</span>
                        <StrokeIcon name="arrow-right" size={20} />
                      </button>
                    ))}
                  </div>
                )}
                {msg.id === messages[messages.length - 1].id && msg.followUps?.length > 0 && <div className="wp-chatbot-followups">
                  {msg.followUps.map((action) => <button type="button" key={action.label} disabled={isTyping} onClick={() => handleSend(action.query)}>{action.label}</button>)}
                </div>}
              </div>
            ))}

            {/* Typing Indicator */}
            {isTyping && (
              <div className="wp-chatbot-typing" aria-label="Assistant is typing">
                <WaviMascot variant="compact" animation={mascot} active={isOpen} />
                <span className="wp-chatbot-typing-dot" />
                <span className="wp-chatbot-typing-dot" />
                <span className="wp-chatbot-typing-dot" />
              </div>
            )}

          </div>

          <div className="wp-chatbot-feedback" role="status" aria-live="polite">
            {feedback === 'success' && <><span aria-hidden="true">&#10003;</span> All checked! Latest info loaded.</>}
            {feedback === 'error' && <>A small hiccup. Try again or use a shortcut above.</>}
          </div>
          {/* Input Bar */}
          <form
            className="wp-chatbot-input-form"
            onSubmit={(e) => {
              e.preventDefault();
              handleSend();
            }}
          >
            <input
              ref={inputRef}
              type="text"
              className="wp-chatbot-input"
              value={inputValue}
              maxLength={2000}
              onChange={(e) => { setInputValue(e.target.value); animateMascot({ type: 'INPUT_CHANGED', hasInput: Boolean(e.target.value.trim()) }); }}
              onFocus={() => animateMascot({ type: 'WAKE' })}
              onKeyDown={handleKeyDown}
              placeholder="Ask Wavy about your workday..."
              aria-label="Ask assistant a question"
            />
            <button
              type="submit"
              className="wp-chatbot-send-btn"
              disabled={!inputValue.trim() || isTyping}
              aria-label="Send message"
              title="Send"
            >
              <StrokeIcon name="send" />
            </button>
          </form>
        </div>
    </>
  );
}
