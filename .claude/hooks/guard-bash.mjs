// PreToolUse(Bash): blocks dangerous commands or ones that bypass the SDLC flow.
// Exit 2 + stderr = command blocked, the message goes back to Claude.
import { readFileSync } from "node:fs";

let input = {};
try { input = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { process.exit(0); }
const cmd = String(input?.tool_input?.command ?? "");

const rules = [
  [/\bgit\s+push\b[^\n;&|]*(\s--force(?!-with-lease)\b|\s-f\b)/, "force push forbidden: use a new commit, or --force-with-lease only when the user explicitly asks"],
  [/\bgit\s+push\b[^\n;&|]*(\s|:)main(\s|$|[;&|])/, "direct push to main forbidden: open a PR with /sdlc-pr"],
  [/--no-verify\b/, "--no-verify forbidden: fix the problem reported by the hooks"],
  [/\bgit\s+reset\s+--hard\b/, "git reset --hard needs explicit user confirmation: ask first"],
  [/\bgit\s+clean\s+-[a-z]*f/, "git clean -f needs explicit user confirmation: ask first"],
  [/\brm\s+-[a-z]*r[a-z]*f?[a-z]*\s+(\/|~|\$HOME|[A-Za-z]:[\\/]?)(\s|$)/i, "rm -r on root/home/drive forbidden"],
  [/\badb\b[^\n;&|]*\bshell\s+(rm|su|setprop|settings\s+put)\b/, "destructive/system adb command: ask the user"],
  [/\bgh\s+repo\s+(delete|edit)\b/, "changing GitHub repository settings is forbidden for agents"],
];

for (const [re, msg] of rules) {
  if (re.test(cmd)) {
    process.stderr.write(`Blocked by guard-bash: ${msg}\nCommand: ${cmd}\n`);
    process.exit(2);
  }
}
process.exit(0);
