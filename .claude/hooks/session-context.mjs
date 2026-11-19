// SessionStart: inietta nel contesto branch, issue collegata e stato del piano.
import { execSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const sh = (c) => { try { return execSync(c, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch { return ""; } };

const branch = sh("git branch --show-current") || "(detached)";
const dirty = sh("git status --porcelain").split("\n").filter(Boolean).length;
const lines = [`Branch: ${branch}${dirty ? ` (${dirty} file modificati)` : ""}`];

const m = branch.match(/^(?:feat|fix|perf|chore)\/(\d+)-/);
if (m) {
  const n = m[1];
  lines.push(`Issue collegata: #${n}`);
  try {
    const plan = readdirSync(join(root, "docs/plans")).find((f) => f.startsWith(`${n}-`));
    if (plan) {
      const txt = readFileSync(join(root, "docs/plans", plan), "utf8");
      const state = (txt.match(/Stato:\s*(\w+)/) || [])[1] ?? "?";
      const todo = (txt.match(/^\s*- \[ \]/gm) || []).length;
      const done = (txt.match(/^\s*- \[x\]/gim) || []).length;
      lines.push(`Piano: docs/plans/${plan} — stato ${state}, passi ${done}/${done + todo}`);
    } else {
      lines.push("Piano: assente → usa /sdlc-plan prima di implementare");
    }
  } catch { lines.push("Piano: assente → usa /sdlc-plan prima di implementare"); }
} else if (branch === "main") {
  lines.push("Sei su main: per modifiche crea un branch (feat/<issue>-<slug>) seguendo docs/SDLC.md");
}

console.log(`[RiriProjector] ${lines.join(" · ")}`);
