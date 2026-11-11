/**
 * Template application rule.
 *
 * A template always *replaces* the note. The old flow appended the template to
 * whatever was already written, which silently doubled text. When the note
 * already has content the UI asks first; the value is the same either way.
 */
export interface TemplateChange {
  /** The note after the template is applied — never a concatenation. */
  next: string;
  /** True when the current note is non-empty and the user must confirm. */
  needsConfirm: boolean;
}

export function templateChange(current: string, template: string): TemplateChange {
  return {
    next: template,
    needsConfirm: current.trim().length > 0 && template !== current,
  };
}
