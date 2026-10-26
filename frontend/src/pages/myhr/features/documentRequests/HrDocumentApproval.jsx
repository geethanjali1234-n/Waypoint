import { useEffect, useState } from 'react';
import { Button } from '../../../../components';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import ApprovalBanner from './ApprovalBanner';
import { approverLabel } from './offerLetterState';
import DocumentFulfillmentEmailComposer from './DocumentFulfillmentEmailComposer';

const errorMessage = (error, fallback) => error?.response?.data?.message || fallback;

/**
 * HR's way of delivering an uploaded document (a plain completed document, or LCA / PERM / I-140) to an employee.
 * HR cannot share it directly: the file goes to the Company Admin for approval, and only once approved can HR send it.
 * The server enforces this; this panel just shows the right step for the request's approval state.
 */
export default function HrDocumentApproval({ request, label, uploadOnly = false, accept, onStatusChange, onDelivered }) {
  const [approval, setApproval] = useState(null);
  const [file, setFile] = useState(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const [organizationName, setOrganizationName] = useState('');
  const status = request.status;
  const approver = approverLabel(organizationName);

  useEffect(() => {
    let active = true;
    Promise.resolve(myHrEmployeeApi.getMine()).then((response) => {
      if (active) setOrganizationName(response?.data?.data?.organizationName || '');
    }).catch(() => { /* falls back to "Company Admin" */ });
    return () => { active = false; };
  }, []);

  // PENDING has never been submitted; the other states need the Admin's reason / the submitted file details.
  useEffect(() => {
    if (status === 'PENDING') { setApproval(null); return undefined; }
    let active = true;
    Promise.resolve(myHrDocumentRequestApi.getDocumentApproval(request.id)).then((response) => {
      if (active && response?.data?.data) setApproval(response.data.data);
    }).catch(() => { /* the state banner still shows from the request status */ });
    return () => { active = false; };
  }, [request.id, status]);

  const submit = async () => {
    setWorking(true); setError('');
    try {
      const response = await myHrDocumentRequestApi.submitDocumentForApproval(request.id, {
        employeeName: request.requestedByName || '',
      }, file);
      setApproval(response.data.data);
      setFile(null);
      onStatusChange('SUBMITTED');
    } catch (failure) {
      setError(errorMessage(failure, `The ${label} could not be submitted for approval. Please try again.`));
    } finally { setWorking(false); }
  };

  const send = async (email) => {
    setWorking(true); setError('');
    try {
      onDelivered((await myHrDocumentRequestApi.deliverApprovedDocument(request.id, email)).data.data);
    } catch (failure) {
      setError(errorMessage(failure, `The ${label} could not be sent. Please try again.`));
      throw failure;
    } finally { setWorking(false); }
  };

  const rejected = status === 'RETURNED_FOR_CORRECTION';
  const pending = status === 'SUBMITTED';
  const approved = status === 'APPROVED';
  const needsFile = status === 'PENDING' || rejected;

  return (
    <div className="document-requests__fulfill">
      {uploadOnly && needsFile && <div>
        <h3>{`Upload ${label}`}</h3>
        <p>Upload the {label} for {request.requestedByName}. It goes to the {approver} for approval before it can be shared with the employee.</p>
      </div>}
      {error && <p role="alert" className="document-requests__error">{error}</p>}
      {rejected && <ApprovalBanner variant="rejected" label="ADMIN APPROVAL REJECTED"
        message={`The ${approver} rejected this ${label}. Upload a corrected file and submit it for approval again.`}>
        {approval?.rejectionReason ? <p className="offer-approval__reason"><strong>Reason:</strong> {approval.rejectionReason}</p> : null}
      </ApprovalBanner>}
      {pending && <ApprovalBanner variant="pending" label="PENDING ADMIN APPROVAL"
        message={`This ${label} has been submitted to the ${approver} for approval. You can send it to the employee only after approval.`} />}
      {approved && <>
        <ApprovalBanner variant="approved" label="APPROVED"
          message={`This ${label} has been approved by the ${approver} and is ready to be sent to the employee.`} />
        <DocumentFulfillmentEmailComposer requestId={request.id} attachmentFileName={approval?.fileName || `${label}.pdf`}
          sendLabel="Send to Employee" onSend={send} />
      </>}
      {needsFile && <>
        <label>{uploadOnly ? `${label} document` : 'Completed document'}
          <input type="file" accept={accept} onChange={(event) => setFile(event.target.files?.[0] || null)} />
        </label>
        <Button disabled={!file} loading={working} onClick={submit}>{rejected ? 'Resubmit for Approval' : 'Submit for Admin Approval'}</Button>
      </>}
    </div>
  );
}
