import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Mirrors the backend's PasswordPolicy exactly (see gateway-api's auth package) -- keep both in sync if this ever changes. */
export const PASSWORD_REQUIREMENTS_MESSAGE =
  'Password must be at least 8 characters and include at least one number and one special character.';

const DIGIT = /\d/;
const SPECIAL_CHARACTER = /[^A-Za-z0-9]/;

export const strongPasswordValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value: string = control.value ?? '';
  if (value.length >= 8 && DIGIT.test(value) && SPECIAL_CHARACTER.test(value)) {
    return null;
  }
  return { strongPassword: true };
};
