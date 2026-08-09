import { readFileSync, statSync } from "node:fs";
import path from "node:path";
import type { SpawnOptions } from "node:child_process";

export interface CodexFileSystem {
  readText(filePath: string): string;
  isFile(filePath: string): boolean;
  isDirectory(filePath: string): boolean;
}

export interface CodexExecutable {
  command: string;
  pathDirs: string[];
}

export interface CodexSpawnSpec {
  command: string;
  args: string[];
  options: SpawnOptions;
}

const nodeFileSystem: CodexFileSystem = {
  readText(filePath) {
    return readFileSync(filePath, "utf8");
  },
  isFile(filePath) {
    try {
      return statSync(filePath).isFile();
    } catch {
      return false;
    }
  },
  isDirectory(filePath) {
    try {
      return statSync(filePath).isDirectory();
    } catch {
      return false;
    }
  },
};

function windowsPath(environment: NodeJS.ProcessEnv): string {
  const key = Object.keys(environment).find((candidate) => candidate.toLowerCase() === "path");
  return key ? environment[key] ?? "" : "";
}

function packageRootFromShim(
  shimPath: string,
  source: string,
): string | undefined {
  const relativeEntry = /%(?:~dp0|dp0%)[\\/]*node_modules[\\/]@openai[\\/]codex[\\/]bin[\\/]codex\.js/i;
  if (relativeEntry.test(source)) {
    return path.win32.join(
      path.win32.dirname(shimPath),
      "node_modules",
      "@openai",
      "codex",
    );
  }

  const absoluteEntry = source.match(
    /([a-z]:[\\/][^"\r\n]*?[\\/]node_modules[\\/]@openai[\\/]codex)[\\/]bin[\\/]codex\.js/i,
  );
  return absoluteEntry?.[1];
}

function uniquePaths(paths: string[]): string[] {
  const seen = new Set<string>();
  return paths.filter((candidate) => {
    const key = candidate.toLowerCase();
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function nativePackageRoots(codexPackageRoot: string, platformPackage: string): string[] {
  const nodeModulesRoot = path.win32.dirname(path.win32.dirname(codexPackageRoot));
  return uniquePaths([
    path.win32.join(codexPackageRoot, "node_modules", "@openai", platformPackage),
    path.win32.join(nodeModulesRoot, "@openai", platformPackage),
    codexPackageRoot,
  ]);
}

function nativeExecutable(
  codexPackageRoot: string,
  targetTriple: string,
  platformPackage: string,
  fileSystem: CodexFileSystem,
): CodexExecutable | undefined {
  for (const packageRoot of nativePackageRoots(codexPackageRoot, platformPackage)) {
    const targetRoot = path.win32.join(packageRoot, "vendor", targetTriple);
    const currentBinary = path.win32.join(targetRoot, "bin", "codex.exe");
    if (fileSystem.isFile(currentBinary)) {
      const bundledPath = path.win32.join(targetRoot, "codex-path");
      return {
        command: currentBinary,
        pathDirs: fileSystem.isDirectory(bundledPath) ? [bundledPath] : [],
      };
    }

    const legacyBinary = path.win32.join(targetRoot, "codex", "codex.exe");
    if (fileSystem.isFile(legacyBinary)) {
      const bundledPath = path.win32.join(targetRoot, "path");
      return {
        command: legacyBinary,
        pathDirs: fileSystem.isDirectory(bundledPath) ? [bundledPath] : [],
      };
    }
  }
  return undefined;
}

export function resolveCodexExecutable(
  arch: NodeJS.Architecture = process.arch,
  environment: NodeJS.ProcessEnv = process.env,
  fileSystem: CodexFileSystem = nodeFileSystem,
): CodexExecutable {
  const target = arch === "x64"
    ? {
        triple: "x86_64-pc-windows-msvc",
        packageName: "codex-win32-x64",
      }
    : arch === "arm64"
      ? {
          triple: "aarch64-pc-windows-msvc",
          packageName: "codex-win32-arm64",
        }
      : undefined;
  if (!target) {
    throw new Error(`Unsupported Windows architecture for Codex: ${arch}`);
  }

  let foundShim = false;
  for (const rawDirectory of windowsPath(environment).split(";")) {
    const directory = rawDirectory.trim().replace(/^"|"$/g, "");
    if (!directory) {
      continue;
    }

    const directExecutable = path.win32.join(directory, "codex.exe");
    if (fileSystem.isFile(directExecutable)) {
      return { command: directExecutable, pathDirs: [] };
    }

    const shimPath = path.win32.join(directory, "codex.cmd");
    if (!fileSystem.isFile(shimPath)) {
      continue;
    }
    foundShim = true;
    try {
      const packageRoot = packageRootFromShim(shimPath, fileSystem.readText(shimPath));
      if (!packageRoot) {
        continue;
      }
      const executable = nativeExecutable(
        packageRoot,
        target.triple,
        target.packageName,
        fileSystem,
      );
      if (executable) {
        return executable;
      }
    } catch {
      // Match PATH lookup: inaccessible and malformed shims are skipped.
    }
  }

  throw new Error(
    foundShim
      ? "Codex native executable not found behind codex.cmd on PATH"
      : "Codex CLI not found on PATH",
  );
}

function environmentWithPath(
  environment: NodeJS.ProcessEnv,
  pathDirs: string[],
): NodeJS.ProcessEnv {
  const result = { ...environment };
  const matchingKeys = Object.keys(result).filter((key) => key.toLowerCase() === "path");
  const pathKey = matchingKeys.includes("Path") ? "Path" : matchingKeys.at(-1) ?? "PATH";
  for (const key of matchingKeys) {
    if (key !== pathKey) {
      delete result[key];
    }
  }
  const prepended = new Set(pathDirs.map((entry) => entry.toLowerCase()));
  const existing = (result[pathKey] ?? "")
    .split(";")
    .filter((entry) => entry && !prepended.has(entry.toLowerCase()));
  result[pathKey] = [...pathDirs, ...existing].join(";");
  return result;
}

export function codexSpawnSpec(
  port: number,
  platform: NodeJS.Platform = process.platform,
  resolveExecutable: () => CodexExecutable = resolveCodexExecutable,
  environment: NodeJS.ProcessEnv = process.env,
): CodexSpawnSpec {
  const executable = platform === "win32"
    ? resolveExecutable()
    : { command: "codex", pathDirs: [] };
  const options: SpawnOptions = {
    shell: false,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", "ignore", "pipe"],
  };
  if (executable.pathDirs.length > 0) {
    options.env = environmentWithPath(environment, executable.pathDirs);
  }
  return {
    command: executable.command,
    args: ["app-server", "--listen", `ws://127.0.0.1:${port}`],
    options,
  };
}
