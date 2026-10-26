import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Dropdown,
  EmailInput,
  PageHeader,
  PhoneInput,
  TextInput,
  ValidationMessage,
} from '../../components';
import { addEmployee, getEligibleReportingManagers, getMyHrSession } from './mockDirectory';
import '../EmployerDashboardPage.css';
import './MyHrCreateEmployeePage.css';

const EMPTY_FORM = {
  name: '',
  personalEmail: '',
  phoneNumber: '',
  state: '',
  city: '',
  streetAddress: '',
  zipCode: '',
  hourlyRate: '',
  designation: '',
  reportsTo: '',
};

/** HR employee creation form backed by the local My HR directory preview. */
export default function MyHrCreateEmployeePage() {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState(null);
  const [createdEmployee, setCreatedEmployee] = useState(null);

  const managerOptions = getEligibleReportingManagers().map((manager) => ({
    value: manager.loginId,
    label: `${manager.loginId} - ${manager.name} - ${manager.role === 'COMPANY_ADMIN' ? 'Company Admin' : 'HR'}`,
  }));

  const handleChange = (field) => (event) => {
    setForm((prev) => ({ ...prev, [field]: event.target.value }));
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    setError(null);

    if (!form.name.trim() || !form.personalEmail.trim() || !form.designation.trim() || !form.hourlyRate) {
      setError('Name, personal email, designation, and hourly rate are required.');
      return;
    }

    if (!form.reportsTo) {
      setError('Reporting Manager is required.');
      return;
    }

    const employee = addEmployee(form);
    setCreatedEmployee(employee);
  };

  if (createdEmployee) {
    return (
      <div className="employer-dashboard__container">
        <Card title="Employee created" className="myhr-create-employee__success">
          <p>
            <strong>{createdEmployee.name}</strong> was created with Employee ID{' '}
            <strong>{createdEmployee.loginId}</strong>.
          </p>
          <p>
            A temporary password was generated and credentials would be emailed to{' '}
            {createdEmployee.personalEmail}.
          </p>
          <p>
            Reporting Manager: <strong>{createdEmployee.reportsTo}</strong>
          </p>
          <div className="myhr-create-employee__success-actions">
            <Button variant="primary" onClick={() => navigate('/myhr/hr/direct-reports')}>
              View my direct reports
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                setCreatedEmployee(null);
                setForm(EMPTY_FORM);
              }}
            >
              Create another employee
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="employer-dashboard__container">
      <PageHeader title="Create Employee" subtitle={`Add a new Employee to ${session?.name ? 'your organization' : ''}.`} />

      <Card className="myhr-create-employee__card">
        <form onSubmit={handleSubmit} noValidate>
          {error && <ValidationMessage message={error} variant="error" />}

          <TextInput label="Name" name="name" value={form.name} onChange={handleChange('name')} required />
          <EmailInput
            label="Personal Email"
            name="personalEmail"
            value={form.personalEmail}
            onChange={handleChange('personalEmail')}
            required
          />
          <PhoneInput
            label="Phone Number"
            name="phoneNumber"
            value={form.phoneNumber}
            onChange={handleChange('phoneNumber')}
          />

          <div className="myhr-create-employee__row">
            <TextInput label="State" name="state" value={form.state} onChange={handleChange('state')} />
            <TextInput label="City" name="city" value={form.city} onChange={handleChange('city')} />
          </div>

          <div className="myhr-create-employee__row">
            <TextInput
              label="Street Address"
              name="streetAddress"
              value={form.streetAddress}
              onChange={handleChange('streetAddress')}
            />
            <TextInput label="ZIP Code" name="zipCode" value={form.zipCode} onChange={handleChange('zipCode')} />
          </div>

          <div className="myhr-create-employee__row">
            <TextInput
              label="Hourly Rate"
              name="hourlyRate"
              type="number"
              value={form.hourlyRate}
              onChange={handleChange('hourlyRate')}
              required
            />
            <TextInput
              label="Designation"
              name="designation"
              value={form.designation}
              onChange={handleChange('designation')}
              required
            />
          </div>

          <Dropdown
            label="Reporting Manager"
            name="reportsTo"
            options={managerOptions}
            value={form.reportsTo}
            onChange={handleChange('reportsTo')}
            placeholder="Select a Reporting Manager"
            required
            hint="Eligible managers: Company Admin or HR. You are not automatically assigned as the manager."
          />

          <Button type="submit" variant="primary">
            Create Employee
          </Button>
        </form>
      </Card>
    </div>
  );
}
