import { open, stat } from "node:fs/promises";
import { watch, type FSWatcher } from "node:fs";
import type { Logger, SessionMessage, TranscriptUpdate } from "./types";
import { truncateText } from "./session-store";
import { extractMessage } from "./transcript-messages";

const READ_CHUNK_BYTES = 64 * 1024;
export const MAX_TRANSCRIPT_LINE_BYTES = 8 * 1024 * 1024;

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
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

export function contentText(value: unknown): string | undefined {
  if (typeof value === "string") {
    return truncateText(value, 10_000);
  }
  if (!Array.isArray(value)) {
    return undefined;
  }
  const parts: string[] = [];
  for (const item of value) {
    if (typeof item === "string") {
      parts.push(item);
      continue;
    }
    const block = recordValue(item);
    if (block && typeof block.text === "string") {
      parts.push(block.text);
    }
  }
  const joined = truncateText(parts.join(" "), 10_000);
  return joined || undefined;
}

function toolNameFrom(value: unknown): string | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  let result: string | undefined;
  for (const item of value) {
    const block = recordValue(item);
    if (block?.type === "tool_use" && typeof block.name === "string" && block.name) {
      result = block.name;
    }
  }
  return result;
}

function hasErrorFlag(entry: Record<string, unknown>, message: Record<string, unknown> | undefined): boolean {
  if (entry.is_error === true || message?.is_error === true) {
    return true;
  }
  for (const content of [entry.content, message?.content]) {
    if (
      Array.isArray(content) &&
      content.some((item) => recordValue(item)?.is_error === true)
    ) {
      return true;
    }
  }
  return false;
}

function errorText(entry: Record<string, unknown>, message: Record<string, unknown> | undefined): string {
  const error = entry.error;
  if (typeof error === "string") {
    return error;
  }
  const errorRecord = recordValue(error);
  if (typeof errorRecord?.message === "string") {
    return errorRecord.message;
  }
  if (typeof message?.error === "string") {
    return message.error;
  }
  if (typeof entry.result === "string") {
    return entry.result;
  }
  return (
    contentText(message?.content) ||
    contentText(entry.content) ||
    String(entry.subtype || entry.type || "API error")
  );
}

export function extractTranscriptUpdate(
  value: unknown,
  now: number = Date.now(),
): TranscriptUpdate | undefined {
  const entry = recordValue(value);
  if (!entry) {
    return undefined;
  }
  const message = recordValue(entry.message);
  const type = typeof entry.type === "string" ? entry.type.toLowerCase() : "";
  const subtype = typeof entry.subtype === "string" ? entry.subtype.toLowerCase() : "";
  const role = typeof message?.role === "string" ? message.role.toLowerCase() : "";
  const update: TranscriptUpdate = {
    activityAt: timestampFrom(entry.timestamp, now),
  };

  const content = message?.content ?? entry.content;
  if (type === "assistant" || role === "assistant") {
    const text = contentText(content);
    if (text) {
      update.lastAssistantText = truncateText(text, 140);
    }
  }
  const toolName = toolNameFrom(content);
  if (toolName) {
    update.lastTool = toolName;
  }

  if (type.includes("error") || subtype.includes("error") || hasErrorFlag(entry, message)) {
    update.error = truncateText(errorText(entry, message), 140);
  }
  return update;
}

export class TranscriptLineParser {
  private remainder: Buffer[] = [];
  private remainderBytes = 0;
  private discardingOversizedRecord = false;

  constructor(
    private readonly onUpdate: (update: TranscriptUpdate) => void,
    private readonly logger: Logger,
    private readonly now: () => number = Date.now,
    /** Optional: receives each appended line as a displayable message. */
    private readonly onMessage?: (message: SessionMessage) => void,
  ) {}

  push(chunk: Buffer): void {
    let start = 0;
    while (start < chunk.length) {
      if (this.discardingOversizedRecord) {
        const newlineIndex = chunk.indexOf(0x0a, start);
        if (newlineIndex < 0) {
          return;
        }
        this.discardingOversizedRecord = false;
        start = newlineIndex + 1;
        continue;
      }

      const newlineIndex = chunk.indexOf(0x0a, start);
      const end = newlineIndex >= 0 ? newlineIndex : chunk.length;
      const segment = chunk.subarray(start, end);
      if (this.remainderBytes + segment.length > MAX_TRANSCRIPT_LINE_BYTES) {
        this.clearRemainder();
        this.logger.info("transcript_line_oversized", {
          maxLineBytes: MAX_TRANSCRIPT_LINE_BYTES,
        });
        if (newlineIndex < 0) {
          this.discardingOversizedRecord = true;
          return;
        }
        start = newlineIndex + 1;
        continue;
      }
      if (segment.length > 0) {
        this.remainder.push(segment);
        this.remainderBytes += segment.length;
      }
      if (newlineIndex < 0) {
        return;
      }

      let line =
        this.remainder.length === 1
          ? this.remainder[0]
          : Buffer.concat(this.remainder, this.remainderBytes);
      this.clearRemainder();
      if (line.at(-1) === 0x0d) {
        line = line.subarray(0, -1);
      }
      if (line.length > 0) {
        try {
          const entry: unknown = JSON.parse(line.toString("utf8"));
          const update = extractTranscriptUpdate(entry, this.now());
          if (update) {
            this.onUpdate(update);
          }
          if (this.onMessage) {
            const message = extractMessage(entry, this.now());
            if (message) {
              this.onMessage(message);
            }
          }
        } catch {
          this.logger.warn("transcript_line_unparseable", { lineBytes: line.length });
        }
      }
      start = newlineIndex + 1;
    }
  }

  reset(): void {
    this.clearRemainder();
    this.discardingOversizedRecord = false;
  }

  private clearRemainder(): void {
    this.remainder = [];
    this.remainderBytes = 0;
  }
}

export interface TranscriptTailerOptions {
  pollIntervalMs?: number;
  startAtEnd?: boolean;
  onMessage?: (message: SessionMessage) => void;
}

export class TranscriptTailer {
  private offset = 0;
  private watcher?: FSWatcher;
  private pollTimer?: NodeJS.Timeout;
  private parser: TranscriptLineParser;
  private running = false;
  private rerun = false;
  private reading?: Promise<void>;
  private readingGeneration?: number;
  private generation = 0;

  constructor(
    private readonly filePath: string,
    onUpdate: (update: TranscriptUpdate) => void,
    private readonly logger: Logger,
    private readonly options: TranscriptTailerOptions = {},
  ) {
    this.parser = new TranscriptLineParser(onUpdate, logger, Date.now, options.onMessage);
  }

  async start(): Promise<void> {
    if (this.running) {
      return;
    }
    this.running = true;
    const generation = ++this.generation;
    let fileSize = 0;
    try {
      fileSize = (await stat(this.filePath)).size;
    } catch {}
    if (!this.isActive(generation)) {
      return;
    }
    this.offset = this.options.startAtEnd === false ? 0 : fileSize;
    this.ensureWatcher(generation);
    const pollIntervalMs = this.options.pollIntervalMs ?? 3000;
    this.pollTimer = setInterval(() => void this.pollGeneration(generation), pollIntervalMs);
    this.pollTimer.unref();
    if (this.options.startAtEnd === false) {
      await this.pollGeneration(generation);
      if (!this.isActive(generation)) {
        return;
      }
    }
  }

  pollNow(): Promise<void> {
    return this.pollGeneration(this.generation);
  }

  private pollGeneration(generation: number): Promise<void> {
    if (!this.isActive(generation)) {
      return Promise.resolve();
    }
    this.rerun = true;
    if (!this.reading || this.readingGeneration !== generation) {
      const operation = this.drainReads(generation).finally(() => {
        if (this.reading === operation) {
          this.reading = undefined;
          this.readingGeneration = undefined;
        }
      });
      this.reading = operation;
      this.readingGeneration = generation;
    }
    return this.reading;
  }

  stop(): void {
    this.running = false;
    this.generation += 1;
    this.rerun = false;
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
    this.watcher?.close();
    this.watcher = undefined;
    this.parser.reset();
  }

  private async drainReads(generation: number): Promise<void> {
    while (this.rerun && this.isActive(generation)) {
      this.rerun = false;
      await this.readAppended(generation);
      if (!this.isActive(generation)) {
        return;
      }
    }
  }

  private async readAppended(generation: number): Promise<void> {
    let fileSize: number;
    try {
      fileSize = (await stat(this.filePath)).size;
    } catch {
      return;
    }
    if (!this.isActive(generation)) {
      return;
    }
    this.ensureWatcher(generation);

    if (fileSize < this.offset) {
      this.offset = 0;
      this.parser.reset();
    }
    if (fileSize === this.offset) {
      return;
    }

    let handle;
    try {
      handle = await open(this.filePath, "r");
      if (!this.isActive(generation)) {
        return;
      }
      while (this.isActive(generation) && this.offset < fileSize) {
        const length = Math.min(READ_CHUNK_BYTES, fileSize - this.offset);
        const buffer = Buffer.allocUnsafe(length);
        const { bytesRead } = await handle.read(buffer, 0, length, this.offset);
        if (!this.isActive(generation)) {
          return;
        }
        if (bytesRead === 0) {
          break;
        }
        this.offset += bytesRead;
        this.parser.push(buffer.subarray(0, bytesRead));
      }
    } catch (error) {
      this.logger.warn("transcript_read_failed", {
        reason:
          error && typeof error === "object" && "code" in error
            ? String(error.code)
            : error instanceof Error
              ? error.name
              : "unknown",
      });
    } finally {
      await handle?.close();
    }
  }

  private isActive(generation: number): boolean {
    return this.running && this.generation === generation;
  }

  private ensureWatcher(generation: number): void {
    if (!this.isActive(generation) || this.watcher) {
      return;
    }
    try {
      const watcher = watch(this.filePath, () => void this.pollGeneration(generation));
      watcher.on("error", () => {
        watcher.close();
        if (this.watcher === watcher) {
          this.watcher = undefined;
        }
      });
      watcher.on("close", () => {
        if (this.watcher === watcher) {
          this.watcher = undefined;
        }
      });
      watcher.unref();
      this.watcher = watcher;
    } catch {
      // The polling fallback will attach a watcher if the file appears later.
    }
  }
}

export class TranscriptTailManager {
  private readonly tailers = new Map<string, { filePath: string; tailer: TranscriptTailer }>();

  constructor(
    private readonly onUpdate: (sessionId: string, update: TranscriptUpdate) => void,
    private readonly logger: Logger,
    private readonly onMessage?: (sessionId: string, message: SessionMessage) => void,
  ) {}

  isTailing(sessionId: string): boolean {
    return this.tailers.has(sessionId);
  }

  start(sessionId: string, filePath: string): void {
    const current = this.tailers.get(sessionId);
    if (current?.filePath === filePath) {
      return;
    }
    current?.tailer.stop();
    const tailer = new TranscriptTailer(
      filePath,
      (update) => this.onUpdate(sessionId, update),
      this.logger,
      { onMessage: this.onMessage ? (message) => this.onMessage?.(sessionId, message) : undefined },
    );
    this.tailers.set(sessionId, { filePath, tailer });
    void tailer.start().catch((error) => {
      this.logger.warn("transcript_tail_start_failed", {
        sessionId: sessionId.slice(0, 8),
        reason: error instanceof Error ? error.name : "unknown",
      });
    });
  }

  stop(sessionId: string): void {
    this.tailers.get(sessionId)?.tailer.stop();
    this.tailers.delete(sessionId);
  }

  stopAll(): void {
    for (const { tailer } of this.tailers.values()) {
      tailer.stop();
    }
    this.tailers.clear();
  }
}
