-- New team members are invited by email with a random server-generated
-- temporary password (see UserService.create() / TempPasswordGenerator) --
-- they must set their own password before they can use the platform.
-- Self-registered tenant admins (AuthService.register()) chose their own
-- password up front, so this defaults to false and register() never sets
-- it true going forward.
ALTER TABLE app_users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- Every user that already exists at the moment this migration runs gets
-- retroactively flagged too -- their current password was never validated
-- against PasswordPolicy (it may predate the policy entirely), so they're
-- brought up to the same "set a real, policy-compliant password before
-- doing anything else" bar as a freshly invited user, not grandfathered in.
UPDATE app_users SET must_change_password = true;
