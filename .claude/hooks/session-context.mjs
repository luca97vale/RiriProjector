// SessionStart: injects current branch, linked issue and plan status into the context.
import { execSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const sh = (c) => { try { return execSync(c, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch { return ""; } };

const branch = sh("git branch --show-current") || "(detached)";
const dirty = sh("git status --porcelain").split("\n").filter(Boolean).length;
const lines = [`Branch: ${branch}${dirty ? ` (${dirty} changed files)` : ""}`];

const m = branch.match(/^(?:feat|fix|perf|chore)\/(\d+)-/);
if (m) {
  const n = m[1];
  lines.push(`Linked issue: #${n}`);
  try {
    const plan = readdirSync(join(root, "docs/plans")).find((f) => f.startsWith(`${n}-`));
    if (plan) {
      const txt = readFileSync(join(root, "docs/plans", plan), "utf8");
      const state = (txt.match(/Status:\s*(\w+)/) || [])[1] ?? "?";
      const todo = (txt.match(/^\s*- \[ \]/gm) || []).length;
      const done = (txt.match(/^\s*- \[x\]/gim) || []).length;
      lines.push(`Plan: docs/plans/${plan} — status ${state}, steps ${done}/${done + todo}`);
    } else {
      lines.push("Plan: missing → run /sdlc-plan before implementing");
    }
  } catch { lines.push("Plan: missing → run /sdlc-plan before implementing"); }
} else if (branch === "main") {
  lines.push("You are on main: create a branch (feat/<issue>-<slug>) for changes, see docs/SDLC.md");
}

console.log(`[RiriProjector] ${lines.join(" · ")}`);
