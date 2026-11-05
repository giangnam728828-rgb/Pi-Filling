/**
 * Phone tools: things the agent can *ask* the host app to do on the device.
 *
 * The agent runs inside a proot rootfs with no route to Android, so each tool
 * is a host-capability request over the JSONL channel (host-channel.mjs). The
 * host decides what is actually allowed — opening an app is a stock Intent the
 * user confirms in the OS's own UI, and the alarm/calendar intents do the same.
 * Nothing here is a privilege escalation: a refusal from the host is reported
 * back as a plain tool result so the model moves on instead of retrying.
 */

import { HostCapabilityError, HostTimeoutError } from "../host-channel.mjs";

const NOTIFY = "phone.notify";
const OPEN = "phone.open";
const ALARM = "phone.alarm";
const TIMER = "phone.timer";
const CALENDAR = "phone.calendar";

/** Same contract as the voice tools' helper: a failure is a result, not a throw. */
function describeFailure(err, what) {
  if (err instanceof HostTimeoutError) {
    return `Could not ${what}: the host did not respond in time. Continue without it.`;
  }
  if (err instanceof HostCapabilityError) {
    if (err.code === "unsupported_capability") {
      return `Could not ${what}: this host has no phone control. Continue in the sandbox and do not try again this session.`;
    }
    if (err.code === "not_permitted") {
      return `Could not ${what}: the user has not granted that permission. Continue and mention it in the reply.`;
    }
    return `Could not ${what}: ${err.message}`;
  }
  return `Could not ${what}: ${err?.message ?? err}`;
}

const ok = (text, details) => ({ content: [{ type: "text", text }], details });

const DAYS = { 1: "monday", 2: "tuesday", 3: "wednesday", 4: "thursday", 5: "friday", 6: "saturday", 7: "sunday" };

/**
 * Build the phone-control tools against a host channel.
 * @param {{request: (cap: string, params?: object) => Promise<object>}} host
 */
export function createPhoneTools(host) {
  return [
    {
      name: "phone_notify",
      label: "Phone Notification",
      description:
        "Post a notification on the user's phone (title + body). Use for reminders the user asked to be surfaced outside this app.",
      parameters: {
        type: "object",
        required: ["title", "text"],
        properties: {
          title: { type: "string", description: "Notification title" },
          text: { type: "string", description: "Notification body" },
        },
      },
      async execute(_id, params) {
        const title = String(params?.title ?? "").trim();
        const text = String(params?.text ?? "").trim();
        if (!title || !text) return ok("Nothing to post: title and text are both required.");
        try {
          await host.request(NOTIFY, { title, text });
          return ok(`Notification posted: "${title}".`);
        } catch (err) {
          return ok(describeFailure(err, "post that notification"));
        }
      },
    },
    {
      name: "phone_open",
      label: "Open App or Link",
      description:
        "Open a link in the browser or launch an app on the user's phone. The OS shows its own confirmation; say what you opened in the reply.",
      parameters: {
        type: "object",
        properties: {
          url: { type: "string", description: "http(s) link or deep link to open" },
          app: { type: "string", description: "App package name (e.g. com.tencent.mm) or exact app name to launch" },
        },
      },
      async execute(_id, params) {
        const url = String(params?.url ?? "").trim();
        const app = String(params?.app ?? "").trim();
        if (!url && !app) return ok("Nothing to open: give a url or an app.");
        try {
          const res = await host.request(OPEN, { url: url || undefined, app: app || undefined });
          return ok(
            res?.opened === "url" ? `Opened ${url} in the browser.` : `Opened ${res?.package ?? app}.`,
            res,
          );
        } catch (err) {
          return ok(describeFailure(err, "open that"));
        }
      },
    },
    {
      name: "phone_alarm",
      label: "Set Alarm",
      description:
        "Set a phone alarm. hour is 0-23, minute is 0-59; optional days repeats it (1=Monday ... 7=Sunday). The clock app confirms with the user.",
      parameters: {
        type: "object",
        required: ["hour", "minute"],
        properties: {
          hour: { type: "number", description: "0-23" },
          minute: { type: "number", description: "0-59" },
          days: { type: "array", items: { type: "number" }, description: "Weekdays to repeat: 1=Mon ... 7=Sun" },
          message: { type: "string", description: "Alarm label" },
        },
      },
      async execute(_id, params) {
        const hour = Number(params?.hour);
        const minute = Number(params?.minute);
        if (!Number.isInteger(hour) || hour < 0 || hour > 23 || !Number.isInteger(minute) || minute < 0 || minute > 59) {
          return ok("Bad alarm time: hour must be 0-23 and minute 0-59.");
        }
        try {
          await host.request(ALARM, {
            hour,
            minute,
            days: params?.days,
            message: params?.message,
          });
          const repeat = Array.isArray(params?.days) && params.days.length
            ? ` (repeats on ${params.days.map((d) => DAYS[d]).filter(Boolean).join(", ")})`
            : "";
          return ok(`Alarm set for ${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}${repeat}.`);
        } catch (err) {
          return ok(describeFailure(err, "set that alarm"));
        }
      },
    },
    {
      name: "phone_timer",
      label: "Set Timer",
      description:
        "Start a countdown timer on the phone that posts a notification when it finishes. seconds is 1-86400.",
      parameters: {
        type: "object",
        required: ["seconds"],
        properties: {
          seconds: { type: "number", description: "1-86400" },
          message: { type: "string", description: "What the finishing notification says" },
        },
      },
      async execute(_id, params) {
        const seconds = Number(params?.seconds);
        if (!Number.isFinite(seconds) || seconds < 1 || seconds > 86400) {
          return ok("Bad timer: seconds must be between 1 and 86400 (24h).");
        }
        try {
          await host.request(TIMER, { seconds, message: params?.message });
          const m = Math.round(seconds / 60);
          return ok(`Timer started (${m} min). It will notify when it ends.`);
        } catch (err) {
          return ok(describeFailure(err, "start that timer"));
        }
      },
    },
    {
      name: "phone_calendar",
      label: "Create Calendar Event",
      description:
        "Create a calendar event on the phone. begin/end are local ISO dates like 2026-10-24T19:00; all_day events take a bare date. The calendar app confirms with the user.",
      parameters: {
        type: "object",
        required: ["title", "begin"],
        properties: {
          title: { type: "string" },
          begin: { type: "string", description: "e.g. 2026-10-24T19:00 or 2026-10-24 for all-day" },
          end: { type: "string", description: "Defaults to begin + 1h" },
          all_day: { type: "boolean" },
          location: { type: "string" },
          description: { type: "string" },
        },
      },
      async execute(_id, params) {
        const title = String(params?.title ?? "").trim();
        const begin = String(params?.begin ?? "").trim();
        if (!title || !begin) return ok("Nothing to create: title and begin are both required.");
        try {
          await host.request(CALENDAR, {
            title,
            begin,
            end: params?.end,
            allDay: params?.all_day,
            location: params?.location,
            description: params?.description,
          });
          return ok(`Calendar event "${title}" queued for confirmation.`);
        } catch (err) {
          return ok(describeFailure(err, "create that event"));
        }
      },
    },
  ];
}
