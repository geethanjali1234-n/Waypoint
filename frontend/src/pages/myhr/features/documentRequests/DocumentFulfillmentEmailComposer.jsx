import { useEffect, useState } from 'react';
import { Button, EmailInput, TextArea, TextInput } from '../../../../components';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';

const emailPattern = /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/;

/**
 * The editable email that accompanies a completed non-offer document. The
 * server remains the source of the recipient and document-specific defaults.
 */
export default function DocumentFulfillmentEmailComposer({ requestId, attachmentFileName, sendLabel = 'Send to Employee', onSend }) {
  const [email, setEmail] = useState({ recipientEmail: '', subject: '', message: '' });
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [errors, setErrors] = useState({});

  useEffect(() => {
    let active = true;
    setLoading(true); setError(''); setErrors({});
    Promise.resolve(myHrDocumentRequestApi.getFulfillmentEmailDraft(requestId))
      .then((response) => {
        if (!active) return;
        const draft = response.data?.data || {};
        setEmail({
          recipientEmail: draft.recipientEmail || '',
          subject: draft.subject || '',
          message: draft.message || '',
        });
      })
      .catch((failure) => {
        if (active) setError(failure?.response?.data?.message || 'The email draft could not be loaded.');
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [requestId]);

  const update = (field) => (event) => setEmail((current) => ({ ...current, [field]: event.target.value }));
  const submit = async () => {
    const nextErrors = {};
    if (!emailPattern.test(email.recipientEmail.trim())) nextErrors.recipientEmail = 'Enter a valid employee email address.';
    if (!email.subject.trim()) nextErrors.subject = 'Enter an email subject.';
    if (!email.message.trim()) nextErrors.message = 'Enter an email message.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    setSending(true); setError('');
    try {
      await onSend({
        recipientEmail: email.recipientEmail.trim(),
        subject: email.subject.trim(),
        message: email.message.trim(),
      });
    } catch (failure) {
      setError(failure?.response?.data?.message || 'The document could not be sent. Please try again.');
    } finally { setSending(false); }
  };

  return <section className="document-email-composer" aria-label="Document delivery email">
    <h3>Send document email</h3>
    <p>The document will be shared in Waypoint and emailed as an attachment. Changes apply to this email only.</p>
    {loading && <p role="status">Loading email template…</p>}
    {error && <p role="alert" className="document-requests__error">{error}</p>}
    {!loading && <>
      <EmailInput name="documentEmailRecipient" label="Recipient Email" required value={email.recipientEmail}
        onChange={update('recipientEmail')} error={errors.recipientEmail}
        hint="Pre-filled from the employee's saved email. Editing it does not change their profile." />
      <TextInput name="documentEmailSubject" label="Subject" required value={email.subject}
        onChange={update('subject')} error={errors.subject} maxLength={200} />
      <TextArea name="documentEmailMessage" label="Message" required rows={9} value={email.message}
        onChange={update('message')} error={errors.message} maxLength={20000} />
      <div className="document-email-composer__attachment"><strong>Attachment</strong><span>{attachmentFileName || 'Document'}</span></div>
      <Button type="button" loading={sending} onClick={submit}>{sendLabel}</Button>
    </>}
  </section>;
}
