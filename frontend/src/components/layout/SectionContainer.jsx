import './SectionContainer.css';

/**
 * SectionContainer - provides consistent page spacing/max-width around content.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} props.children - Section content.
 * @param {'sm'|'md'|'lg'} [props.spacing='md'] - Vertical padding scale.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <SectionContainer spacing="lg">
 *   <PageHeader title="Events near you" />
 * </SectionContainer>
 */
export default function SectionContainer({ children, spacing = 'md', className = '' }) {
  const classes = ['wp-section-container', `wp-section-container--${spacing}`, className]
    .filter(Boolean)
    .join(' ');

  return <section className={classes}>{children}</section>;
}
