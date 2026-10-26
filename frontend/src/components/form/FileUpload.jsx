import FormLabel from './FormLabel';
import './FileUpload.css';

/**
 * FileUpload - resume/document upload control showing the current file
 * and offering upload or replace actions.
 *
 * @param {Object} props
 * @param {string} props.name - Field name, also used to derive the input id.
 * @param {string} [props.label] - Field label text.
 * @param {string} [props.fileName] - Name of the currently attached file, if any.
 * @param {File|Blob} [props.downloadFile] - Locally selected file that can be downloaded from its filename.
 * @param {string} [props.helperText] - Helper text shown below the control.
 * @param {string} [props.accept] - Accepted file types (e.g. ".pdf,.doc,.docx").
 * @param {boolean} [props.multiple=false] - Allows selecting more than one file at once.
 * @param {boolean} [props.disabled=false] - Disables selecting or replacing a file.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onUpload] - Called when a new file is selected and no file exists yet.
 * @param {(event: import('react').ChangeEvent<HTMLInputElement>) => void} [props.onReplace] - Called when a new file is selected to replace an existing one.
 * @param {() => void} [props.onRemove] - Removes the currently attached file.
 * @param {string} [props.removeLabel='Remove file'] - Accessible label for the remove action.
 * @param {string} [props.className=''] - Additional class names.
 *
 * @example
 * <FileUpload label="Resume" name="resume" fileName="jordan_torres_resume.pdf" onReplace={handleReplace} />
 */
export default function FileUpload({
  name,
  label,
  fileName,
  downloadFile,
  helperText,
  accept,
  multiple = false,
  disabled = false,
  onUpload,
  onReplace,
  onRemove,
  removeLabel = 'Remove file',
  className = '',
}) {
  const classes = ['wp-file-upload', className].filter(Boolean).join(' ');
  const handleChange = fileName ? onReplace : onUpload;
  const canDownload = Boolean(fileName && downloadFile);
  const download = () => {
    if (!downloadFile) return;
    const url = URL.createObjectURL(downloadFile);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName || 'download';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  const onFilenameKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      download();
    }
  };

  return (
    <div className={`${classes}${disabled ? ' wp-file-upload--disabled' : ''}`}>
      {label && <FormLabel htmlFor={name}>{label}</FormLabel>}
      <div className="wp-file-upload__control">
        {fileName ? (
          canDownload ? (
            <button type="button" className="wp-file-upload__filename wp-file-upload__filename--downloadable"
              title={`${fileName} — double-click to download`} aria-label={`Download ${fileName}`}
              onDoubleClick={download} onKeyDown={onFilenameKeyDown}>{fileName}</button>
          ) : <span className="wp-file-upload__filename" title={fileName}>{fileName}</span>
        ) : (
          <span className="wp-file-upload__placeholder">No file selected</span>
        )}
        <span className="wp-file-upload__actions">
          <label className={`wp-file-upload__button${disabled ? ' wp-file-upload__button--disabled' : ''}`}>
            {fileName ? 'Replace' : 'Upload'}
            <input
              id={name}
              name={name}
              type="file"
              accept={accept}
              multiple={multiple}
              onChange={handleChange}
              className="wp-file-upload__input"
              disabled={disabled}
            />
          </label>
          {fileName && onRemove && <button type="button" className="wp-file-upload__remove" onClick={onRemove} disabled={disabled} aria-label={removeLabel}>Remove</button>}
        </span>
      </div>
      {helperText && <p className="wp-file-upload__hint">{helperText}</p>}
    </div>
  );
}
