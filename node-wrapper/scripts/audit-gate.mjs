#!/usr/bin/env node
// Production dependency audit gate.
//
// A bare `npm audit --audit-level=high` has already proven too blunt for this
// tree once. @earendil-works/pi-coding-agent 0.78.x shipped an
// npm-shrinkwrap.json, and npm treats a dependency's shrinkwrap as
// authoritative for that subtree — so the vulnerable transitives it pinned
// were not reachable by consumer `overrides` or by editing our own lockfile.
// Both were tried. CI could not go green without either ignoring every high
// advisory or upgrading, and the upgrade was a breaking API migration.
//
// The 0.84.x migration resolved that instance, and the allowlist below is now
// empty. The gate stays because the situation is structural rather than
// hypothetical: an upstream can pin a vulnerable transitive out of our reach
// again, and when it does the choice should be a documented, per-package
// exception rather than lowering the threshold for everything.
//
// The gate fails when:
//   - a high or critical advisory appears for a package not on the allowlist
//   - any critical advisory appears, allowlisted or not
// It warns (without failing) when an allowlisted package no longer reports an
// advisory, so entries get pruned instead of going stale.

import { spawnSync } from "node:child_process";

// Allowlisted: everything below is pinned inside @earendil-works/pi-coding-agent@0.84.3's
// npm-shrinkwrap.json, which npm treats as authoritative for that subtree — the same
// structural problem SECURITY.md recorded for the 0.78.x line. New advisories have since
// been published against the pinned transitives (undici through 8.12.2+, brace-expansion
// through 5.0.11/5.0.12, minimatch through 11.0.1). A root-level `overrides` block was
// retried against 0.84.3 and still loses to the shrinkwrap on install. The only real fix
// is the 0.84.x -> 1.5.x migration (breaking API, not a version bump); until that
// lands these stay documented exceptions. Prune entries when the audit stops
// reporting them (the gate warns on stale entries).
const ALLOWLIST = {
  undici: {
    summary:
      "Pinned at 8.9.0 inside pi-coding-agent@0.84.3's shrinkwrap; DoS advisories " +
      "(incl. TLS cert-validation bypass, unbounded decompression) fixed upstream in " +
      "8.12.2+, unreachable via consumer overrides.",
    reason:
      "npm treats a dependency's npm-shrinkwrap.json as authoritative for its subtree, " +
      "so the pinned 8.9.0 installs regardless of root-level overrides (retried, " +
      "confirmed losing on 0.84.3). Only the 0.84.x -> 1.5.x migration clears it. " +
      "Reachability note: undici is the HTTP client under the provider SDKs; in this " +
      "wrapper it talks outbound HTTPS to LLM endpoints, not to user-supplied input, " +
      "which bounds the DoS surface.",
  },
  "brace-expansion": {
    summary:
      "Pinned at 5.0.9 inside pi-coding-agent@0.84.3's shrinkwrap; quadratic-time " +
      "expansion DoS (GHSA-q2hr-2g5m-vwhr) and nested-brace stack exhaustion " +
      "(GHSA-qhr7-859c-m2p7, GHSA-6j4f-fj2g-mc7p) fixed in 5.0.10/5.0.11+.",
    reason:
      "Same shrinkwrap pin as undici: consumer overrides do not reach this subtree. " +
      "The brace-expansion here is a transitive of glob-style tooling under the " +
      "agent SDK; input to it is internal, not attacker-controlled in this wrapper's " +
      "use. Cleared by the 0.84.x -> 1.5.x migration.",
  },
  minimatch: {
    summary:
      "Pinned at 10.2.5 inside pi-coding-agent@0.84.3's shrinkwrap; globparts " +
      "amplification DoS (GHSA-g3hv-75wg-m249) fixed in 11.0.1. Moderate; " +
      "allowlisted for completeness with the other shrinkwrap-pinned transitives.",
    reason:
      "Same shrinkwrap pin as undici and brace-expansion. Moderate severity, internal " +
      "input only. Cleared by the 0.84.x -> 1.5.x migration.",
  },
  "@earendil-works/pi-coding-agent": {
    summary:
      "Aggregate advisory: the 0.84.3 release is the carrier of the pinned " +
      "undici/minimatch transitives (fixAvailable 1.5.0, major). Moderate on its own; " +
      "allowlisted so the gate's output names the migration target.",
    reason:
      "This is the documented path out of the three transitive allowlist entries: " +
      "bump the agent stack to 1.5.x. Until that migration lands, every new advisory " +
      "on a pinned transitive lands here; prune this and the transitive entries " +
      "together when the bump ships.",
  },
};

const BLOCKING = new Set(["high", "critical"]);

const result = spawnSync("npm", ["audit", "--omit=dev", "--json"], {
  encoding: "utf8",
  // npm audit exits nonzero whenever vulnerabilities exist. That is the normal
  // case here, so the exit code carries no signal — only the JSON does.
  maxBuffer: 32 * 1024 * 1024,
});

if (!result.stdout) {
  console.error("audit-gate: npm audit produced no output");
  if (result.stderr) console.error(result.stderr.trim());
  process.exit(2);
}

let report;
try {
  report = JSON.parse(result.stdout);
} catch (err) {
  console.error(`audit-gate: could not parse npm audit JSON: ${err.message}`);
  process.exit(2);
}

const found = Object.entries(report.vulnerabilities ?? {});
const violations = [];
const accepted = [];

for (const [name, vuln] of found) {
  const severity = vuln.severity;
  const allowed = ALLOWLIST[name];

  if (severity === "critical") {
    // A critical is never waved through, even for an allowlisted package: the
    // reachability arguments below were written against the advisories that
    // existed at the time, and a critical warrants re-deriving them.
    violations.push({
      name,
      severity,
      why: allowed ? "critical overrides the allowlist" : "not allowlisted",
    });
    continue;
  }

  if (!BLOCKING.has(severity)) {
    if (allowed) accepted.push({ name, severity });
    continue;
  }

  if (allowed) accepted.push({ name, severity });
  else violations.push({ name, severity, why: "not allowlisted" });
}

const stale = Object.keys(ALLOWLIST).filter((name) => !found.some(([reported]) => reported === name));

const counts = report.metadata?.vulnerabilities ?? {};
console.log(
  `audit-gate: ${counts.total ?? 0} advisories on production deps ` +
    `(critical ${counts.critical ?? 0}, high ${counts.high ?? 0}, moderate ${counts.moderate ?? 0}, low ${counts.low ?? 0})`,
);

for (const { name, severity } of accepted) {
  console.log(`  accepted  ${severity.padEnd(8)} ${name} — ${ALLOWLIST[name].summary}`);
}

for (const name of stale) {
  console.log(`  stale     allowlist entry for ${name} reports nothing — prune it from audit-gate.mjs`);
}

if (violations.length > 0) {
  console.error("");
  console.error("audit-gate: FAIL — advisories outside the documented allowlist:");
  for (const { name, severity, why } of violations) {
    console.error(`  ${severity.padEnd(8)} ${name} (${why})`);
  }
  console.error("");
  console.error("Fix the dependency, or add an entry to ALLOWLIST in scripts/audit-gate.mjs");
  console.error("with a written reachability argument and update SECURITY.md to match.");
  process.exit(1);
}

console.log("audit-gate: PASS — nothing outside the documented allowlist.");
