import './weekHoursStrip.css';

const dateKey = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/** Daily totals come from entries. Compare schedules only when supplied. */
export default function WeekHoursStrip({ entries = [], weekStart, standardHours }) {
  const first = weekStart || entries.find((entry) => entry.workDate)?.workDate;
  if (!first) return <span>Daily hours unavailable</span>;
  const monday = new Date(`${first}T00:00:00`);
  if (Number.isNaN(monday.getTime())) return <span>Daily hours unavailable</span>;
  monday.setDate(monday.getDate() - (monday.getDay() + 6) % 7);
  const days = Array.from({ length: 5 }, (_, index) => {
    const date = new Date(monday);
    date.setDate(monday.getDate() + index);
    const key = dateKey(date);
    const recorded = entries.filter((entry) => entry.workDate === key && entry.hoursWorked !== null && entry.hoursWorked !== undefined && entry.hoursWorked !== '' && Number.isFinite(Number(entry.hoursWorked)));
    const hours = recorded.length ? recorded.reduce((sum, entry) => sum + Number(entry.hoursWorked), 0) : null;
    return { key, date, hours, differs: hours !== null && Number.isFinite(standardHours) && hours !== standardHours };
  });
  const maximum = Math.max(1, Number.isFinite(standardHours) ? standardHours : 0, ...days.map((day) => day.hours || 0));
  return <div className="week-hours"><span className="week-hours__caption">Week of {monday.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}</span>
    <ol aria-label="Monday to Friday hours" className="week-hours__days">{days.map((day) => <li key={day.key} className={day.differs ? 'week-hours__day--different' : undefined}>
      <span aria-hidden="true" className="week-hours__bar"><span style={{ height: day.hours === null ? '0%' : `${Math.min(100, Math.max(0, day.hours) / maximum * 100)}%` }} /></span>
      <span className="week-hours__label">{day.date.toLocaleDateString('en-US', { weekday: 'short' })}</span>
      <strong>{day.hours === null ? '-' : `${day.hours}h`}</strong>
      <span className="week-hours__sr">{day.date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}{day.hours === null ? ': no entry' : ''}{day.differs ? ': differs from standard schedule' : ''}</span>
    </li>)}</ol>
  </div>;
}
