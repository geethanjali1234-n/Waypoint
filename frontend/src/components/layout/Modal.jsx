import './Modal.css';

/**
 * Modal - centered overlay dialog used for forms and detail views
 * (e.g. job posting form, event form, change password).
 *
 * @param {Object} props
 * @param {boolean} props.isOpen - Whether the modal is visible.
 * @param {string} [props.title] - Modal header title.
 * @param {() => void} [props.onClose] - Called when the close control or backdrop is clicked.
 * @param {import('react').ReactNode} props.children - Modal body content.
 * @param {import('react').ReactNode} [props.footer] - Optional footer content (e.g. action buttons).
 * @param {string} [props.className=''] - Additional class names on the dialog panel.
 *
 * @example
 * <Modal isOpen={isOpen} title="Schedule event" onClose={close} footer={<Button>Save</Button>}>
 *   <TextInput label="Event title" name="title" />
 * </Modal>
 */
export default function Modal({ isOpen, title, onClose, children, footer, className = '' }) {
  if (!isOpen) return null;

  const classes = ['wp-modal__panel', className].filter(Boolean).join(' ');

  return (
    <div className="wp-modal__backdrop" onClick={onClose}>
      <div
        className={classes}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="wp-modal__header">
          {title && <h2 className="wp-modal__title">{title}</h2>}
          {onClose && (
            <button type="button" className="wp-modal__close" aria-label="Close" onClick={onClose}>
              ×
            </button>
          )}
        </div>
        <div className="wp-modal__body">{children}</div>
        {footer && <div className="wp-modal__footer">{footer}</div>}
      </div>
    </div>
  );
}
