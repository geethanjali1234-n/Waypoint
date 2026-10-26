import './AppLayout.css';

/**
 * AppLayout - main page wrapper composing a header, content area, and footer.
 *
 * @param {Object} props
 * @param {import('react').ReactNode} [props.header] - Header content, typically a `Header` component.
 * @param {import('react').ReactNode} [props.footer] - Footer content, typically a `Footer` component.
 * @param {import('react').ReactNode} props.children - Page content rendered between header and footer.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <AppLayout header={<Header />} footer={<Footer />}>
 *   <PageHeader title="Careers" />
 * </AppLayout>
 */
export default function AppLayout({ header, footer, children, className = '' }) {
  const classes = ['wp-app-layout', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {header && <div className="wp-app-layout__header">{header}</div>}
      <main className="wp-app-layout__content">{children}</main>
      {footer && <div className="wp-app-layout__footer">{footer}</div>}
    </div>
  );
}
