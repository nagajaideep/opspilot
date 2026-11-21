package dev.opspilot.user;

/**
 * Least privilege: each role gets only what it needs.
 * REQUESTER - create/submit own change requests, read inventory.
 * APPROVER  - everything a requester can do + approve/reject/execute others' requests.
 * ADMIN     - manage inventory, run policy scans, read the full audit log.
 */
public enum Role { REQUESTER, APPROVER, ADMIN }
