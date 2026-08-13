import { FormControl } from '@angular/forms';
import { strongPasswordValidator } from './password.validator';

describe('strongPasswordValidator', () => {
  it('accepts a password with 8+ chars, a digit, and a special character', () => {
    expect(strongPasswordValidator(new FormControl('p@ssword123'))).toBeNull();
  });

  it('rejects a password shorter than 8 characters', () => {
    expect(strongPasswordValidator(new FormControl('p@ss1'))).toEqual({ strongPassword: true });
  });

  it('rejects a password with no digit', () => {
    expect(strongPasswordValidator(new FormControl('p@ssword!!'))).toEqual({ strongPassword: true });
  });

  it('rejects a password with no special character', () => {
    expect(strongPasswordValidator(new FormControl('password123'))).toEqual({ strongPassword: true });
  });

  it('rejects an empty value', () => {
    expect(strongPasswordValidator(new FormControl(''))).toEqual({ strongPassword: true });
  });
});
