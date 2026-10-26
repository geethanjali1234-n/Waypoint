import { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getMyHrSession } from '../../auth/myHrSession';
import { queryKnowledgeBase, QUICK_SUGGESTIONS, getQuickSuggestions } from './knowledgeBase';
import './WaypointChatbot.css';
import FloatingAssistant from './FloatingAssistant';
import StrokeIcon from '../global/StrokeIcon';

export default function WaypointChatbot({ displayName } = {}) {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const role = session?.role || 'HR';
  const suggestions = getQuickSuggestions ? getQuickSuggestions(role) : QUICK_SUGGESTIONS;

  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState([
    {
      id: 1,
      sender: 'assistant',
      text: `Hi ${session?.loginId || 'there'}! 👋 I'm your **Waypoint Assistant**.\nI can answer questions about timesheets, payroll cutoff, profile updates, and guide you directly to any workflow.\n\nHow can I help you today?`,
      actions: []
    }
  ]);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const messageIdRef = useRef(1);
  const feedEndRef = useRef(null);
  const inputRef = useRef(null);

  // Auto-scroll to latest message
  useEffect(() => {
    if (isOpen) {
      const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      feedEndRef.current?.scrollIntoView?.({ behavior: reducedMotion ? 'auto' : 'smooth' });
    }
  }, [messages, isTyping, isOpen]);

  // Focus input when opened
  useEffect(() => {
    if (isOpen) {
      const timeout = setTimeout(() => inputRef.current?.focus(), 150);
      const closeOnEscape = (event) => {
        if (event.key === 'Escape' && event.target.closest?.('#waypoint-assistant-chat')) {
          event.preventDefault();
          setIsOpen(false);
        }
      };
      document.addEventListener('keydown', closeOnEscape);
      return () => {
        clearTimeout(timeout);
        document.removeEventListener('keydown', closeOnEscape);
        document.querySelector('.wp-floating-assistant')?.focus();
      };
    }
  }, [isOpen]);

  const handleSend = (textToSend) => {
    const query = (textToSend || inputValue).trim();
    if (!query) return;

    const userMessage = {
      id: ++messageIdRef.current,
      sender: 'user',
      text: query,
    };

    setMessages((prev) => [...prev, userMessage]);
    setInputValue('');
    setIsTyping(true);

    // Natural responsive delay
    setTimeout(() => {
      const response = queryKnowledgeBase(query, role);
      const botMessage = {
        id: ++messageIdRef.current,
        sender: 'assistant',
        text: response.text,
        actions: response.actions || []
      };
      setMessages((prev) => [...prev, botMessage]);
      setIsTyping(false);
    }, 350);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleActionClick = (path) => {
    if (!path) return;
    navigate(path);
  };

  const handleClearChat = () => {
    setMessages([
      {
        id: ++messageIdRef.current,
        sender: 'assistant',
        text: "Chat cleared! How else can I assist you?",
        actions: []
      }
    ]);
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
      {!isOpen && (
        <FloatingAssistant onClick={() => setIsOpen(true)} controls="waypoint-assistant-chat" />
      )}

      {/* Chat Window */}
      {isOpen && (
        <div id="waypoint-assistant-chat" className="wp-chatbot-window" role="dialog" aria-label="Waypoint Assistant Chat">
          {/* Header */}
          <div className="wp-chatbot-header">
            <div className="wp-chatbot-header__info">
              <div className="wp-chatbot-header__avatar" aria-hidden="true"><StrokeIcon name="help" /></div>
              <div className="wp-chatbot-header__title">
                <strong>Waypoint Assistant</strong>
                <small>Online · Instant Answers</small>
              </div>
            </div>
            <div className="wp-chatbot-header__actions">
              <button
                type="button"
                className="wp-chatbot-header__btn"
                onClick={handleClearChat}
                title="Clear conversation"
                aria-label="Clear chat"
              >
                <StrokeIcon name="trash" />
              </button>
              <button
                type="button"
                className="wp-chatbot-header__btn"
                onClick={() => setIsOpen(false)}
                title="Close assistant"
                aria-label="Close assistant"
              >
                <StrokeIcon name="close" />
              </button>
            </div>
          </div>

          {/* Quick Suggestions Carousel */}
          <div className="wp-chatbot-suggestions" aria-label="Suggested topics">
            {suggestions.map((sug, i) => (
              <button
                type="button"
                key={i}
                className="wp-chatbot-suggestion-chip"
                onClick={() => handleSend(sug.query)}
              >
                {sug.label}
              </button>
            ))}
          </div>

          {/* Conversation Feed */}
          <div className="wp-chatbot-feed">
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={`wp-chatbot-message wp-chatbot-message--${msg.sender}`}
              >
                <div className="wp-chatbot-bubble">
                  {renderFormattedText(msg.id === 1 && displayName ? msg.text.replace(/^Hi [^!]+!/, `Hi ${displayName}!`) : msg.text)}
                </div>

                {/* Optional Action Deep Links */}
                {msg.actions && msg.actions.length > 0 && (
                  <div className="wp-chatbot-actions">
                    {msg.actions.map((act, idx) => (
                      <button
                        type="button"
                        key={idx}
                        className="wp-chatbot-action-btn"
                        onClick={() => handleActionClick(act.path)}
                      >
                        <span>{act.label}</span>
                        <StrokeIcon name="arrow-right" size={20} />
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}

            {/* Typing Indicator */}
            {isTyping && (
              <div className="wp-chatbot-typing" aria-label="Assistant is typing">
                <span className="wp-chatbot-typing-dot" />
                <span className="wp-chatbot-typing-dot" />
                <span className="wp-chatbot-typing-dot" />
              </div>
            )}
            <div ref={feedEndRef} />
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
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask anything or search workflows..."
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
      )}
    </>
  );
}
