# VT 4.0.2 — direct shoreline/configuration saves

VersionName 4.0.2 / versionCode 25. At the user's request, shoreline library and selected-ID writes directly overwrite the existing JSON file. No `.backup` file is created and no rollback write is attempted. Read-back verification remains; missing files, folder permission problems and mismatched contents are reported as save failures. Existing backup files are left untouched.

This removes the backup-creation path that blocked shoreline saving. First-time folder setup remains absent-only and preserves existing configurations. Flight behavior, JSON schema and all VT 4.0.1 UI/logging fixes remain unchanged.
