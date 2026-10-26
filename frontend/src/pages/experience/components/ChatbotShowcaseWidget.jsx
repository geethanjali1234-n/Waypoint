import { useRef, useState } from 'react';
import { queryKnowledgeBase } from '../../../components/chatbot/knowledgeBase';

const FEATURED_PROMPTS = [
  {
    icon: '⏰',
    label: 'Submission Deadline',
    query: 'When should I submit my timesheet?',
  },
  {
    icon: '⚖️',
    label: 'FLSA Overtime Rules',
    query: 'How is overtime calculated under FLSA rules?',
  },
  {
    icon: '🤖',
    label: 'LLM & OCR Parsing',
    query: 'How does AI timesheet filling work with LLM and OCR fallback?',
  },
  {
    icon: '📝',
    label: 'Profile Updates',
    query: 'How do I submit an address or personal info update?',
  },
];

const INITIAL_MESSAGES = [
  {
    id: 1,
    sender: 'assistant',
    text: 'Hello! I am your **Waypoint AI Assistant**. I provide 24/7 instant guidance on payroll deadlines, FLSA overtime policies, document verification, and direct report approvals.\n\nSelect a common inquiry below or type any question to test my knowledge base:',
    actions: [
      { label: 'Jump to Timesheets Sandbox ↓', action: '#section-timesheets' },
      { label: 'Compare Legacy Platforms ↓', action: '#section-comparison' },
    ],
  },
];

export default function ChatbotShowcaseWidget() {
  const messageId = useRef(1);
  const [messages, setMessages] = useState(INITIAL_MESSAGES);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);

  const handleAsk = (queryText) => {
    const q = (queryText || inputValue).trim();
    if (!q) return;

    const userMsg = { id: ++messageId.current, sender: 'user', text: q };
    setMessages((prev) => [...prev, userMsg]);
    setInputValue('');
    setIsTyping(true);

    setTimeout(() => {
      // Query knowledge base or custom rich answers
      let response;
      if (q.toLowerCase().includes('llm') || q.toLowerCase().includes('ocr') || q.toLowerCase().includes('filling')) {
        response = {
          text: '**Dual-Engine Timesheet Parsing Architecture:**\n\n1. **Primary Engine (LLM API Key)**: Uses your configured LLM API key for natural language comprehension of arbitrary document layouts, handwritten dates, and notes.\n2. **Automatic Fallback (High-Precision OCR)**: If the LLM API key is unconfigured, rate-limited, or offline, Waypoint automatically switches to deterministic optical OCR & tabular regex extraction with zero downtime.\n3. **Human-in-the-Loop Review**: Extracted dates and hours are pre-filled into the timesheet for final employee confirmation before submission.',
          actions: [
            { label: 'Open Ingestion Scanner ↓', action: '#section-documents' },
            { label: 'Test Timesheet Simulator ↓', action: '#section-timesheets' },
          ],
        };
      } else {
        response = queryKnowledgeBase(q, 'HR');
      }

      const botMsg = {
        id: ++messageId.current,
        sender: 'assistant',
        text: response.text,
        actions: response.actions || [],
      };
      setMessages((prev) => [...prev, botMsg]);
      setIsTyping(false);
    }, 400);
  };

  const handleActionClick = (actionTarget) => {
    if (!actionTarget) return;
    if (actionTarget.startsWith('#')) {
      const el = document.querySelector(actionTarget);
      el?.scrollIntoView({ behavior: 'smooth' });
    }
  };

  const renderFormattedText = (text) => {
    return text.split('\n').map((paragraph, pIdx) => {
      if (!paragraph.trim()) return <br key={pIdx} />;
      const parts = paragraph.split(/(\*\*.*?\*\*)/g);
      return (
        <p key={pIdx} className="wp-chat-showcase-p">
          {parts.map((part, i) => {
            if (part.startsWith('**') && part.endsWith('**')) {
              return <strong key={i}>{part.slice(2, -2)}</strong>;
            }
            return part;
          })}
        </p>
      );
    });
  };

  return (
    <div className="wp-chat-showcase-card">
      <div className="wp-chat-showcase-header">
        <div className="wp-chat-showcase-header-left">
          <span className="wp-chat-showcase-badge">24/7 WORKFORCE INTELLIGENCE</span>
          <h3 className="wp-chat-showcase-title">Embedded AI HR Assistant & Knowledge Engine</h3>
          <p className="wp-chat-showcase-desc">
            Equip employees and managers with instant answers to payroll cutoffs, statutory overtime questions, and compliance rules. Includes one-click action buttons that take users straight to the relevant workflow.
          </p>
        </div>

        <div className="wp-chat-showcase-pills-bar">
          <span className="wp-chat-showcase-pills-label">QUICK INQUIRIES:</span>
          <div className="wp-chat-showcase-chips">
            {FEATURED_PROMPTS.map((p) => (
              <button
                key={p.label}
                type="button"
                className="wp-chat-showcase-chip"
                onClick={() => handleAsk(p.query)}
              >
                <span className="wp-chat-showcase-chip-icon" aria-hidden="true">{p.icon}</span>
                <span>{p.label}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="wp-chat-showcase-window">
        <div className="wp-chat-showcase-feed">
          {messages.map((m) => (
            <div
              key={m.id}
              className={`wp-chat-showcase-bubble wp-chat-showcase-bubble--${m.sender}`}
            >
              <div className="wp-chat-showcase-sender-tag">
                {m.sender === 'assistant' ? '✦ WAYPOINT AI ASSISTANT' : 'YOU'}
              </div>
              <div className="wp-chat-showcase-body">{renderFormattedText(m.text)}</div>

              {m.actions && m.actions.length > 0 && (
                <div className="wp-chat-showcase-actions">
                  {m.actions.map((act, idx) => (
                    <button
                      key={idx}
                      type="button"
                      className="wp-chat-showcase-action-btn"
                      onClick={() => handleActionClick(act.action || act.path)}
                    >
                      {act.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}

          {isTyping && (
            <div className="wp-chat-showcase-bubble wp-chat-showcase-bubble--assistant wp-chat-showcase-bubble--typing">
              <span className="wp-chat-showcase-dot" />
              <span className="wp-chat-showcase-dot" />
              <span className="wp-chat-showcase-dot" />
            </div>
          )}
        </div>

        <form
          className="wp-chat-showcase-input-bar"
          onSubmit={(e) => {
            e.preventDefault();
            handleAsk();
          }}
        >
          <input
            type="text"
            className="wp-chat-showcase-input"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder="Ask about overtime, timesheets, LLM parsing, or profile updates..."
          />
          <button type="submit" className="wp-chat-showcase-send-btn">
            Send Inquiry →
          </button>
        </form>
      </div>

      <div className="wp-chat-showcase-footer">
        <div className="wp-chat-showcase-guarantee">
          <span className="wp-chat-showcase-lock">🔒</span>
          <span>
            <strong>Zero Data Leakage:</strong> Adheres to strict Role-Based Access Control (RBAC). Employees only receive guidance and information permitted for their authorized role.
          </span>
        </div>
      </div>
    </div>
  );
}
