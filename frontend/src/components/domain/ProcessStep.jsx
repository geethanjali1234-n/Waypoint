import './ProcessStep.css';

/**
 * ProcessStep - single numbered step used to build "How it works" sequences
 * (e.g. "1. Post", "2. Match", "3. Hire").
 *
 * @param {Object} props
 * @param {number} props.stepNumber - Step order, displayed as the numeral.
 * @param {string} props.title - Step title.
 * @param {string} [props.description] - Step description.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <ProcessStep stepNumber={1} title="Post a role" description="Publish a role with the skills you need." />
 */
export default function ProcessStep({ stepNumber, title, description, className = '' }) {
  const classes = ['wp-process-step', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      <span className="wp-process-step__number">{stepNumber}</span>
      <p className="wp-process-step__title">{title}</p>
      {description && <p className="wp-process-step__description">{description}</p>}
    </div>
  );
}
