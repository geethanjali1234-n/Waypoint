import { Modal } from '../layout';
import { Button } from '../global';

/**
 * ConfirmationDialog - modal prompt confirming a destructive or important action
 * (e.g. cancelling a registration, closing a job posting).
 *
 * @param {Object} props
 * @param {boolean} props.isOpen - Whether the dialog is visible.
 * @param {string} props.title - Dialog title.
 * @param {import('react').ReactNode} props.message - Confirmation message/body text.
 * @param {string} [props.confirmLabel='Confirm'] - Label for the confirm button.
 * @param {string} [props.cancelLabel='Cancel'] - Label for the cancel button.
 * @param {() => void} props.onConfirm - Called when the confirm button is clicked.
 * @param {() => void} props.onCancel - Called when the cancel button or backdrop is clicked.
 * @param {string} [props.className=''] - Additional class names on the modal panel.
 *
 * @example
 * <ConfirmationDialog
 *   isOpen={isOpen}
 *   title="Cancel registration?"
 *   message="You can register again anytime before the event closes."
 *   onConfirm={handleConfirm}
 *   onCancel={handleCancel}
 * />
 */
export default function ConfirmationDialog({
  isOpen,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  className = '',
}) {
  return (
    <Modal
      isOpen={isOpen}
      title={title}
      onClose={onCancel}
      className={className}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button variant="primary" onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p>{message}</p>
    </Modal>
  );
}
