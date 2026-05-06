import {readFileSync} from "fs"
import {parse as parseYaml} from "yaml"
import {GrokMessage} from "./grok.js"

const ALLOWED_ROLES: ReadonlySet<GrokMessage["role"]> = new Set(["system", "user", "assistant"])

// Reads a YAML file in the harness transcript format — a flat list of
// `{role, message}` entries with `role ∈ {system, user, assistant}` —
// and returns it as `GrokMessage[]` ready to prepend to every Grok call.
//
// Throws on malformed YAML, non-list top level, non-mapping entries,
// unknown roles, or non-string messages — the file is operator-supplied
// configuration, so a typo should fail-fast at startup, not silently
// degrade Grok responses.
export function loadGrokContext(path: string): GrokMessage[] {
  const text = readFileSync(path, "utf-8")
  let raw: unknown
  try {
    raw = parseYaml(text)
  } catch (e) {
    throw new Error(`${path}: failed to parse YAML: ${(e as Error).message}`)
  }
  if (raw === null || raw === undefined) return []
  if (!Array.isArray(raw)) {
    throw new Error(`${path}: top-level must be a list, got ${typeof raw}`)
  }
  const context: GrokMessage[] = []
  for (let i = 0; i < raw.length; i++) {
    const entry = raw[i]
    if (entry === null || typeof entry !== "object" || Array.isArray(entry)) {
      throw new Error(`${path}: entry ${i} is not a mapping`)
    }
    const {role, message} = entry as {role?: unknown; message?: unknown}
    if (typeof role !== "string" || !ALLOWED_ROLES.has(role as GrokMessage["role"])) {
      throw new Error(`${path}: entry ${i} has invalid role: ${JSON.stringify(role)}`)
    }
    if (typeof message !== "string") {
      throw new Error(`${path}: entry ${i} has non-string message`)
    }
    context.push({role: role as GrokMessage["role"], content: message})
  }
  return context
}
