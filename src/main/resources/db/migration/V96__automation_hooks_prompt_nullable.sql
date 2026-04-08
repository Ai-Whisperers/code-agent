-- Allow hooks that use a built-in job (e.g. service_desk_triage) to omit a custom prompt.
ALTER TABLE automation_hooks ALTER COLUMN prompt DROP NOT NULL;
