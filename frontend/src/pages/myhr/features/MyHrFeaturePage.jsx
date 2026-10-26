import { PageHeader } from '../../../components';
import MyHrShell from '../MyHrShell';
import PageBackButton from './PageBackButton';
import './myHrFeatures.css';
import './phaseSix.css';

export default function MyHrFeaturePage({
  eyebrow,
  title,
  description,
  children,
  className = '',
  onBack,
  fullWidth = false,
  hideBack = false,
  backTo,
  backLabel = 'Back',
  headerAction,
  breadcrumb,
}) {
  const classes = ['myhr-feature', fullWidth && 'myhr-feature--full', className].filter(Boolean).join(' ');
  const showHeader = Boolean(title || description || headerAction);

  return (
    <MyHrShell fullWidth={fullWidth}>
      <div className={classes}>
        <div className="myhr-feature__heading">
        {breadcrumb}
        {!hideBack && (
          <PageBackButton onClick={onBack} backTo={backTo} label={backLabel} />
        )}
        {showHeader && <PageHeader eyebrow={eyebrow} title={title} subtitle={description} action={headerAction} />}
        </div>
        <div className="myhr-feature__body">{children}</div>
      </div>
    </MyHrShell>
  );
}
