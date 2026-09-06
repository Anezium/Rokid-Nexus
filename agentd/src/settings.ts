import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

export const HOOK_EVENTS = [
  "SessionStart",
  "UserPromptSubmit",
  // PreToolUse tracks what a session is doing; PermissionRequest is the one the
  // wearer is asked to answer, because it fires only when a decision is
  // actually needed. See decisionResponse in approval-manager.
  "PreToolUse",
  "PermissionRequest",
  "Stop",
  "SubagentStop",
  "Notification",
  "SessionEnd",
] as const;

export interface SettingsUpdateResult {
  changed: boolean;
  backupPath?: string;
  settingsPath: string;
}

export interface SettingsOptions {
  settingsPath?: string;
  now?: () => Date;
}

function defaultSettingsPath(): string {
  return path.join(homedir(), ".claude", "settings.json");
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function containsForwarder(value: unknown): boolean {
  const hook = objectValue(value);
  return typeof hook?.command === "string" && hook.command.includes("hook-forward.js");
}

function parseSettings(filePath: string): { settings: Record<string, unknown>; existed: boolean } {
  if (!existsSync(filePath)) {
    return { settings: {}, existed: false };
  }
  const raw = readFileSync(filePath, "utf8");
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    throw new Error(
      `Cannot parse Claude settings at ${filePath}; no changes were made: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
  const settings = objectValue(parsed);
  if (!settings) {
    throw new Error(`Claude settings at ${filePath} must contain a JSON object; no changes were made`);
  }
  return { settings, existed: true };
}

function hooksObject(settings: Record<string, unknown>, create: boolean): Record<string, unknown> | undefined {
  if (settings.hooks === undefined) {
    if (!create) {
      return undefined;
    }
    const hooks: Record<string, unknown> = {};
    settings.hooks = hooks;
    return hooks;
  }
  const hooks = objectValue(settings.hooks);
  if (!hooks) {
    throw new Error("Claude settings 'hooks' must be a JSON object; no changes were made");
  }
  return hooks;
}

function formatTimestamp(date: Date): string {
  const part = (value: number) => String(value).padStart(2, "0");
  return [
    date.getFullYear(),
    part(date.getMonth() + 1),
    part(date.getDate()),
    "-",
    part(date.getHours()),
    part(date.getMinutes()),
    part(date.getSeconds()),
  ].join("");
}

function backupCurrent(filePath: string, now: () => Date): string {
  let date = now();
  let backupPath = `${filePath}.agentd-backup-${formatTimestamp(date)}`;
  while (existsSync(backupPath)) {
    date = new Date(date.getTime() + 1000);
    backupPath = `${filePath}.agentd-backup-${formatTimestamp(date)}`;
  }
  copyFileSync(filePath, backupPath);
  return backupPath;
}

function writeSettings(
  filePath: string,
  settings: Record<string, unknown>,
  existed: boolean,
  now: () => Date,
): string | undefined {
  mkdirSync(path.dirname(filePath), { recursive: true });
  const backupPath = existed ? backupCurrent(filePath, now) : undefined;
  const tempPath = `${filePath}.${process.pid}.tmp`;
  writeFileSync(tempPath, `${JSON.stringify(settings, null, 2)}\n`, "utf8");
  renameSync(tempPath, filePath);
  return backupPath;
}

export function installHooks(
  agentRoot: string,
  options: SettingsOptions = {},
): SettingsUpdateResult {
  const filePath = options.settingsPath ?? defaultSettingsPath();
  const { settings, existed } = parseSettings(filePath);
  const hooks = hooksObject(settings, true)!;
  const command = `node "${path.resolve(agentRoot, "dist", "hook-forward.js")}"`;
  let changed = false;

  for (const eventName of HOOK_EVENTS) {
    const current = hooks[eventName];
    if (current !== undefined && !Array.isArray(current)) {
      throw new Error(`Claude settings hook '${eventName}' must be an array; no changes were made`);
    }
    const groups = (current ?? []) as unknown[];
    const alreadyInstalled = groups.some((groupValue) => {
      if (containsForwarder(groupValue)) {
        return true;
      }
      const group = objectValue(groupValue);
      return Array.isArray(group?.hooks) && group.hooks.some(containsForwarder);
    });
    if (!alreadyInstalled) {
      groups.push({
        matcher: "",
        hooks: [{ type: "command", command }],
      });
      hooks[eventName] = groups;
      changed = true;
    }
  }

  if (!changed) {
    return { changed: false, settingsPath: filePath };
  }
  const backupPath = writeSettings(filePath, settings, existed, options.now ?? (() => new Date()));
  return { changed: true, backupPath, settingsPath: filePath };
}

export function uninstallHooks(options: SettingsOptions = {}): SettingsUpdateResult {
  const filePath = options.settingsPath ?? defaultSettingsPath();
  const { settings, existed } = parseSettings(filePath);
  const hooks = hooksObject(settings, false);
  if (!hooks) {
    return { changed: false, settingsPath: filePath };
  }
  let changed = false;

  for (const [eventName, eventValue] of Object.entries(hooks)) {
    if (!Array.isArray(eventValue)) {
      continue;
    }
    const nextGroups: unknown[] = [];
    for (const groupValue of eventValue) {
      if (containsForwarder(groupValue)) {
        changed = true;
        continue;
      }
      const group = objectValue(groupValue);
      if (!group || !Array.isArray(group.hooks)) {
        nextGroups.push(groupValue);
        continue;
      }
      const nextHooks = group.hooks.filter((hook) => !containsForwarder(hook));
      if (nextHooks.length !== group.hooks.length) {
        changed = true;
      }
      if (nextHooks.length > 0 || group.hooks.length === 0) {
        nextGroups.push({ ...group, hooks: nextHooks });
      }
    }
    hooks[eventName] = nextGroups;
  }

  if (!changed) {
    return { changed: false, settingsPath: filePath };
  }
  const backupPath = writeSettings(filePath, settings, existed, options.now ?? (() => new Date()));
  return { changed: true, backupPath, settingsPath: filePath };
}
