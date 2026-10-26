import './TwoColumnLayout.css';

/**
 * TwoColumnLayout - side-by-side layout used on list+detail and detail+form pages
 * (e.g. Careers list/detail, Job Detail content/apply form).
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.main - Primary (typically wider) column content.
 * @param {import('react').ReactNode} props.side - Secondary column content.
 * @param {'1:1'|'2:1'|'3:1'} [props.ratio='2:1'] - Relative width ratio of main to side column.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <TwoColumnLayout main={<JobDetailCard />} side={<ApplicationForm />} ratio="2:1" />
 */
export default function TwoColumnLayout({ main, side, ratio = '2:1', className = '' }) {
  const classes = ['wp-two-column', `wp-two-column--${ratio.replace(':', '-')}`, className]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classes}>
      <div className="wp-two-column__main">{main}</div>
      <div className="wp-two-column__side">{side}</div>
    </div>
  );
}
