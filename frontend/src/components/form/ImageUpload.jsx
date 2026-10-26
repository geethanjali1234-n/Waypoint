import FormLabel from './FormLabel';
import { Avatar } from '../global';
import './ImageUpload.css';

/**
 * ImageUpload - profile picture / company logo upload control with preview.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.previewUrl] - Current image preview URL, if any.
 * @param {string} [props.initials] - Fallback initials shown when no preview is available.
 * @param {string} [props.helperText] - Helper text shown below the control.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onUpload] - Called when a new image is selected.
 * @param {() => void} [props.onRemove] - When provided, renders a "Remove photo" button that calls this handler.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <ImageUpload label="Profile picture" name="profilePicture" initials="JT" onUpload={handleUpload} onRemove={handleRemove} />
 */
export default function ImageUpload({
  name,
  label,
  previewUrl,
  initials = '',
  helperText,
  onUpload,
  onRemove,
  className = '',
}) {
  const classes = ['wp-image-upload', className].filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {label && <FormLabel htmlFor={name}>{label}</FormLabel>}
      <div className="wp-image-upload__control">
        <Avatar imageUrl={previewUrl} initials={initials} size="lg" />
        <label className="wp-image-upload__button">
          Add photo
          <input
            id={name}
            name={name}
            type="file"
            accept="image/*"
            onChange={onUpload}
            className="wp-image-upload__input"
          />
        </label>
        {onRemove && (
          <button type="button" className="wp-image-upload__remove" onClick={onRemove}>
            Remove photo
          </button>
        )}
      </div>
      {helperText && <p className="wp-image-upload__hint">{helperText}</p>}
    </div>
  );
}
