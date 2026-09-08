import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import os from "node:os";
import path from "node:path";
import type { AgentConfig } from "./types";

const DEFAULT_HTTP_PORT = 8791;
const DEFAULT_WS_PORT = 8792;
export const DEFAULT_CODEX_PORT = 8390;
export const DEFAULT_PHONE_LINK_PORT = 8792;

export function defaultStateDir(): string {
  return process.env.NEXUS_AGENTD_STATE_DIR || path.join(homedir(), ".nexus-agentd");
}

export function configPath(stateDir = defaultStateDir()): string {
  return path.join(stateDir, "config.json");
}

function newConfig(): AgentConfig {
  return {
    token: randomBytes(32).toString("base64url"),
    wsPort: DEFAULT_WS_PORT,
    httpPort: DEFAULT_HTTP_PORT,
    machineId: randomBytes(16).toString("base64url"),
    machineName: os.hostname(),
    phoneHosts: [],
    tailnetDiscovery: true,
    allowTerminalInput: false,
    codex: {
      enabled: false,
      port: DEFAULT_CODEX_PORT,
    },
  };
}

function isPort(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) > 0 && Number(value) <= 65_535;
}

export function parsePhoneTarget(value: string): { host: string; port: number } | undefined {
  if (!value || value.trim() !== value) {
    return undefined;
  }
  const separator = value.lastIndexOf(":");
  if (separator < 0) {
    return { host: value, port: DEFAULT_PHONE_LINK_PORT };
  }
  const host = value.slice(0, separator);
  const portText = value.slice(separator + 1);
  if (!host || !/^\d+$/.test(portText)) {
    return undefined;
  }
  const port = Number(portText);
  return isPort(port) ? { host, port } : undefined;
}

function parseConfig(raw: string, filePath: string): {
  config: AgentConfig;
  upgraded: boolean;
} {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch (error) {
    throw new Error(`Cannot parse ${filePath}: ${error instanceof Error ? error.message : String(error)}`);
  }

  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }

  const record = value as Record<string, unknown>;
  if (
    typeof record.token !== "string" ||
    record.token.length === 0 ||
    !isPort(record.wsPort) ||
    !isPort(record.httpPort) ||
    typeof record.machineName !== "string" ||
    record.machineName.length === 0
  ) {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }

  const missingMachineId = typeof record.machineId !== "string" || record.machineId.length === 0;
  const hasPhoneHosts = Object.prototype.hasOwnProperty.call(record, "phoneHosts");
  const phoneHosts = Array.isArray(record.phoneHosts) ? record.phoneHosts : undefined;
  if (
    (hasPhoneHosts && !phoneHosts) ||
    (phoneHosts && phoneHosts.some((entry) => typeof entry !== "string" || !parsePhoneTarget(entry)))
  ) {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }
  const hasCodex = Object.prototype.hasOwnProperty.call(record, "codex");
  const codexRecord =
    record.codex && typeof record.codex === "object" && !Array.isArray(record.codex)
      ? record.codex as Record<string, unknown>
      : undefined;
  if (
    (hasCodex && !codexRecord) ||
    (codexRecord &&
      (typeof codexRecord.enabled !== "boolean" || !isPort(codexRecord.port)))
  ) {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }
  const hasTailnetDiscovery = Object.prototype.hasOwnProperty.call(record, "tailnetDiscovery");
  if (hasTailnetDiscovery && typeof record.tailnetDiscovery !== "boolean") {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }
  const hasTerminalInput = Object.prototype.hasOwnProperty.call(record, "allowTerminalInput");
  if (hasTerminalInput && typeof record.allowTerminalInput !== "boolean") {
    throw new Error(`Invalid nexus-agentd config at ${filePath}`);
  }
  const upgraded =
    missingMachineId || !hasPhoneHosts || !hasCodex || !hasTailnetDiscovery || !hasTerminalInput;
  return {
    config: {
      token: record.token,
      wsPort: record.wsPort,
      httpPort: record.httpPort,
      machineId: missingMachineId
        ? randomBytes(16).toString("base64url")
        : record.machineId as string,
      machineName: record.machineName,
      phoneHosts: (phoneHosts as string[] | undefined) ?? [],
      tailnetDiscovery:
        typeof record.tailnetDiscovery === "boolean" ? record.tailnetDiscovery : true,
      // Absent reads as off: an upgrade must never hand a phone the ability to
      // type into a terminal that did not have it before.
      allowTerminalInput: record.allowTerminalInput === true,
      codex: codexRecord
        ? {
            enabled: codexRecord.enabled as boolean,
            port: codexRecord.port as number,
          }
        : {
            enabled: false,
            port: DEFAULT_CODEX_PORT,
          },
    },
    upgraded,
  };
}

function writeConfig(filePath: string, config: AgentConfig): void {
  const tempPath = `${filePath}.${process.pid}.tmp`;
  writeFileSync(tempPath, `${JSON.stringify(config, null, 2)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
  renameSync(tempPath, filePath);
}

export function saveConfig(config: AgentConfig, stateDir = defaultStateDir()): void {
  mkdirSync(stateDir, { recursive: true });
  writeConfig(configPath(stateDir), config);
}

export function readPhoneLinkConfig(filePath: string): Pick<
  AgentConfig,
  "phoneHosts" | "tailnetDiscovery"
> {
  const config = parseConfig(readFileSync(filePath, "utf8"), filePath).config;
  return {
    phoneHosts: [...config.phoneHosts],
    tailnetDiscovery: config.tailnetDiscovery,
  };
}

export function ensureConfig(stateDir = defaultStateDir()): AgentConfig {
  mkdirSync(stateDir, { recursive: true });
  const filePath = configPath(stateDir);
  if (!existsSync(filePath)) {
    const config = newConfig();
    writeConfig(filePath, config);
    return config;
  }

  const parsed = parseConfig(readFileSync(filePath, "utf8"), filePath);
  if (parsed.upgraded) {
    writeConfig(filePath, parsed.config);
  }
  return parsed.config;
}
