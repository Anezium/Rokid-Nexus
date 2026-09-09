import { open, stat } from "node:fs/promises";
import type { MessageRole, SessionMessage } from "./types";

const TAIL_READ_BYTES = 512 * 1024;
export const MAX_TEXT_CHARS = 3500;
const MAX_TOOL_SUMMARY_CHARS = 90;

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

export function condense(value: string, maxLength = MAX_TEXT_CHARS): string {
  const normalized = value
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/[ \t]+/g, " ").trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  return normalized.slice(0, Math.max(0, maxLength));
}

function condenseSingleLine(value: string, maxLength: number): string {
  return value
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t\n]+/g, " ")
    .trim()
    .slice(0, Math.max(0, maxLength));
}

function timestampFrom(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value < 1_000_000_000_000 ? value * 1000 : value;
  }
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

function textFrom(content: unknown): string {
  if (typeof content === "string") {
    return condense(content);
  }
  if (!Array.isArray(content)) {
    return "";
  }
  const parts: string[] = [];
  for (const item of content) {
    if (typeof item === "string") {
      parts.push(item);
      continue;
    }
    const block = recordValue(item);
    if (block?.type === "text" && typeof block.text === "string") {
      parts.push(block.text);
    }
  }
  return condense(parts.join("\n"));
}

function toolBlock(content: unknown): Record<string, unknown> | undefined {
  if (!Array.isArray(content)) {
    return undefined;
  }
  for (const item of content) {
    const block = recordValue(item);
    if (block?.type === "tool_use") {
      return block;
    }
  }
  return undefined;
}

function hasToolResult(content: unknown): boolean {
  return Array.isArray(content) &&
    content.some((item) => recordValue(item)?.type === "tool_result");
}

/** Absolute paths waste a HUD line; the last two segments locate a file well enough. */
function shortenPath(value: string): string {
  const segments = value.split(/[\\/]+/).filter(Boolean);
  return segments.length <= 2 ? value : `…/${segments.slice(-2).join("/")}`;
}

/**
 * How many lines actually changed between two edit sides, without pulling in
 * a real diff library for a HUD glance stat: trim the common prefix and
 * suffix and count what's left on each side. Exact for the common case an
 * editor's old/new-string edit produces — one contiguous replaced block —
 * and never worse than an honest line count for anything stranger.
 */
function lineDiffStats(oldText: string, newText: string): { added: number; removed: number } {
  const oldLines = oldText.split("\n");
  const newLines = newText.split("\n");
  let start = 0;
  while (
    start < oldLines.length &&
    start < newLines.length &&
    oldLines[start] === newLines[start]
  ) {
    start += 1;
  }
  let oldEnd = oldLines.length;
  let newEnd = newLines.length;
  while (
    oldEnd > start &&
    newEnd > start &&
    oldLines[oldEnd - 1] === newLines[newEnd - 1]
  ) {
    oldEnd -= 1;
    newEnd -= 1;
  }
  return { removed: oldEnd - start, added: newEnd - start };
}

function diffSuffix(added: number, removed: number): string {
  return added === 0 && removed === 0 ? "" : ` (+${added} -${removed})`;
}

/** The wearer's one glance at what an edit tool actually did to a file. */
function editStatsSuffix(name: string, values: Record<string, unknown>): string {
  if (
    name === "Edit" &&
    typeof values.old_string === "string" &&
    typeof values.new_string === "string"
  ) {
    const stats = lineDiffStats(values.old_string, values.new_string);
    return diffSuffix(stats.added, stats.removed);
  }
  if (name === "MultiEdit" && Array.isArray(values.edits)) {
    let added = 0;
    let removed = 0;
    for (const edit of values.edits) {
      const record = recordValue(edit);
      if (typeof record?.old_string === "string" && typeof record?.new_string === "string") {
        const stats = lineDiffStats(record.old_string, record.new_string);
        added += stats.added;
        removed += stats.removed;
      }
    }
    return diffSuffix(added, removed);
  }
  if (name === "Write" && typeof values.content === "string") {
    const lines = values.content.length === 0 ? 0 : values.content.split("\n").length;
    return ` (${lines} line${lines === 1 ? "" : "s"})`;
  }
  return "";
}

/** The one input field that says what a tool call is actually doing. */
export function toolSummary(name: string, input: unknown): string {
  const label = condenseSingleLine(name, MAX_TOOL_SUMMARY_CHARS);
  const values = recordValue(input) ?? {};
  const pathValue = [values.file_path, values.path, values.notebook_path]
    .find((value): value is string => typeof value === "string" && value.trim().length > 0);
  const candidate = pathValue
    ? shortenPath(pathValue)
    : [
        values.command,
        values.pattern,
        values.description,
        values.prompt,
        values.url,
        values.query,
      ].find((value): value is string => typeof value === "string" && value.trim().length > 0);
  const suffix = editStatsSuffix(name, values);
  if (!candidate) {
    return condenseSingleLine(`${label}${suffix}`, MAX_TOOL_SUMMARY_CHARS);
  }
  const prefix = `${label} · `;
  const detail = condenseSingleLine(candidate, MAX_TOOL_SUMMARY_CHARS - prefix.length - suffix.length);
  return detail
    ? `${prefix}${detail}${suffix}`
    : condenseSingleLine(`${label}${suffix}`, MAX_TOOL_SUMMARY_CHARS);
}

/**
 * Turns one transcript line into a displayable message, or undefined for the
 * entries a wearer should never see: tool results, sidechains, meta records.
 */
export function extractMessage(value: unknown, now: number = Date.now()): SessionMessage | undefined {
  const entry = recordValue(value);
  if (!entry || entry.isSidechain === true || entry.isMeta === true) {
    return undefined;
  }
  const message = recordValue(entry.message);
  const content = message?.content ?? entry.content;
  const type = typeof entry.type === "string" ? entry.type.toLowerCase() : "";
  const role = typeof message?.role === "string" ? message.role.toLowerCase() : type;
  const at = timestampFrom(entry.timestamp, now);

  if (role === "user") {
    if (hasToolResult(content)) {
      return undefined;
    }
    const text = textFrom(content);
    // Local command chatter (<command-name>…) is plumbing, not conversation.
    if (!text || text.startsWith("<")) {
      return undefined;
    }
    return { role: "user" as MessageRole, text, at };
  }

  if (role === "assistant") {
    const text = textFrom(content);
    if (text) {
      return { role: "assistant" as MessageRole, text, at };
    }
    const tool = toolBlock(content);
    if (tool && typeof tool.name === "string") {
      return {
        role: "tool" as MessageRole,
        text: toolSummary(tool.name, tool.input),
        at,
        tool: tool.name,
      };
    }
  }

  return undefined;
}

/** Reads the tail of a transcript and returns its last [limit] messages. */
export async function readRecentMessages(
  filePath: string,
  limit: number,
): Promise<SessionMessage[]> {
  let size: number;
  try {
    size = (await stat(filePath)).size;
  } catch {
    return [];
  }
  const start = Math.max(0, size - TAIL_READ_BYTES);
  const length = size - start;
  if (length <= 0) {
    return [];
  }

  let handle;
  try {
    handle = await open(filePath, "r");
    const buffer = Buffer.allocUnsafe(length);
    const { bytesRead } = await handle.read(buffer, 0, length, start);
    let slice = buffer.subarray(0, bytesRead);
    if (start > 0) {
      // A partial first line would fail to parse; drop it.
      const newline = slice.indexOf(0x0a);
      slice = newline >= 0 ? slice.subarray(newline + 1) : Buffer.alloc(0);
    }
    const messages: SessionMessage[] = [];
    for (const line of slice.toString("utf8").split("\n")) {
      if (!line.trim()) {
        continue;
      }
      try {
        const message = extractMessage(JSON.parse(line));
        if (message) {
          messages.push(message);
        }
      } catch {
        // Truncated or malformed line: skip it, never fail the whole read.
      }
    }
    return messages.slice(-limit);
  } catch {
    return [];
  } finally {
    await handle?.close();
  }
}
