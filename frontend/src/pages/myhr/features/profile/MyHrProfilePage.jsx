import { useEffect, useState } from 'react';
import { Button, Card, EmptyState, Loader, StatusBadge } from '../../../../components';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import { getMyHrHomePath } from '../../../../auth/myHrSession';
import { formatDateOnly } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import ProfileSignatureCard from './ProfileSignatureCard';
import AccountEmailCard from './AccountEmailCard';
import './myHrProfile.css';

const FALLBACK = 'Not provided';

function display(value) {
  return value === null || value === undefined || value === '' ? FALLBACK : String(value);
}

function formatDate(value) {
  return value ? formatDateOnly(value) : FALLBACK;
}

function formatEnum(value) {
  return value ? value.toLowerCase().split('_').map((part) => part[0].toUpperCase() + part.slice(1)).join(' ') : FALLBACK;
}

function initials(value) {
  const parts = String(value || '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  return parts.slice(0, 2).map((part) => part[0]).join('').toUpperCase();
}

function formatReportingManager(manager) {
  if (!manager) return 'Not assigned';
  const identifier = manager.employeeId || manager.userId || manager.loginId || '';
  const name = manager.name || '';
  if (identifier && name) return `${identifier} · ${name}`;
  if (identifier) return identifier;
  if (name) return name;
  return 'Not assigned';
}

function Fields({ items }) {
  return <dl className="myhr-profile__fields">{items.map(([label, value]) => (
    <div key={label}><dt>{label}</dt><dd>{display(value)}</dd></div>
  ))}</dl>;
}

export default function MyHrProfilePage({ role }) {
  const [profile, setProfile] = useState(null);
  const [state, setState] = useState('loading');

  const load = () => {
    setState('loading');
    myHrEmployeeApi.getMine()
      .then((response) => {
        setProfile(response.data?.data || null);
        setState('success');
      })
      .catch(() => setState('error'));
  };

  useEffect(load, []);

  const page = (content) => <MyHrFeaturePage hideBack className="phase-six myhr-profile-page" title="My Profile" description="View your account, personal, employment, and organization information." backTo={`${getMyHrHomePath(role)}/dashboard`}><div className="myhr-profile">{content}</div></MyHrFeaturePage>;
  if (state === 'loading') return page(<Loader label="Loading your profile…" />);
  if (state === 'error') return page(<EmptyState title="Couldn't load your profile" description="Something went wrong loading your profile." action={<Button type="button" variant="secondary" onClick={load}>Try Again</Button>} />);
  if (!profile) return page(<EmptyState title="Profile unavailable" />);

  const isAdmin = role === 'COMPANY_ADMIN';
  const primaryIdentity = profile.name?.trim() || profile.organizationAdminName?.trim() || [profile.firstName, profile.lastName].filter(Boolean).join(' ') || profile.loginId;
  const identityId = isAdmin ? profile.loginId : profile.employeeId;

  return page(<>
    <section className="myhr-profile__identity">
      <div className="myhr-profile__avatar" aria-hidden="true">{initials(primaryIdentity)}</div>
      <div className="myhr-profile__identity-copy">
        <h2>{display(primaryIdentity)}</h2>
        <p>{isAdmin ? 'Admin / Login ID' : 'Employee ID'}: {display(identityId)}</p>
      </div>
      <div className="myhr-profile__identity-tags">
        <span className="myhr-profile__badge myhr-profile__badge--role">{formatEnum(profile.role)}</span>
        <StatusBadge status={profile.accountStatus} />
      </div>
    </section>

    <div className="myhr-profile__primary-grid">
      <Card title="Account Information"><Fields items={isAdmin ? [
        ['Admin / Login ID', profile.loginId],
        ['Role', formatEnum(profile.role)],
        ['Account Status', formatEnum(profile.accountStatus)],
      ] : [
        [role === 'HR' ? 'HR / Employee ID' : 'Employee ID', profile.employeeId],
        ['First Name', profile.firstName],
        ['Last Name', profile.lastName],
        ['Role', formatEnum(profile.role)],
        ['Account Status', formatEnum(profile.accountStatus)],
      ]} /></Card>
      <Card title="Contact Information"><Fields items={isAdmin ? [
        ['Organization Email', profile.organizationEmail],
        ['Admin Email', profile.organizationAdminEmail],
      ] : [
        ['Personal Email', profile.personalEmail],
        ['Phone Number', profile.phoneNumber],
        ['Street Address', profile.streetAddress],
        ['City', profile.city],
        ['State', profile.state],
        ['ZIP Code', profile.zipCode],
      ]} /></Card>
    </div>

    {!isAdmin &&
      <Card title="Employment Information"><Fields items={[
        ['Designation', profile.designation], ['Employment Type', formatEnum(profile.employmentType)],
        ['Start Date', formatDate(profile.startDate)],
        ['Reporting Manager', formatReportingManager(profile.reportingManager)],
      ]} /></Card>
    }

    <Card title="Organization"><Fields items={[
      ['Organization', profile.organizationName], ['Organization Code', profile.organizationCode],
    ]} /></Card>

    {isAdmin && <AccountEmailCard accountEmail={profile.accountEmail} onSaved={(email) => setProfile((current) => ({
      ...current, accountEmail: email, organizationAdminEmail: email,
    }))} />}

    <ProfileSignatureCard signatureUrl={profile.signatureUrl} profileName={profile.name || profile.organizationAdminName || profile.loginId || ''} onSaved={(updated) => { if (updated) setProfile(updated); }} />
  </>);
}
