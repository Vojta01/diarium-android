import assert from "node:assert/strict";
import test from "node:test";

import { seedDefaultScales, type Scale } from "../src/lib/scales.ts";
import { DEFAULT_SCALE_DEFS, missingDefaultScales } from "../src/lib/checkin/scales.ts";

function scale(overrides: Partial<Scale> & { name: string }): Scale {
  return {
    id: `id-${overrides.name}`,
    user_id: "user-1",
    emoji: "⭐",
    min_value: 1,
    max_value: 5,
    color: "#888888",
    sort_order: 99,
    is_active: true,
    created_at: "2026-01-01T00:00:00.000Z",
    ...overrides,
  };
}

test("seeding creates only missing defaults and never touches custom scales", async () => {
  const custom = scale({ name: "Únava", min_value: 0, max_value: 10 });
  const drifted = scale({
    name: "Energie",
    min_value: 1,
    max_value: 10,
    color: "#000000",
  });
  const created: string[] = [];

  const result = await seedDefaultScales({
    read: async () => [custom, drifted],
    create: async (def) => {
      created.push(def.name);
      return scale({ ...def });
    },
  });

  // "Energie" already exists (with a different range) — it is the user's data,
  // so only "Produktivita" is missing.
  assert.deepEqual(created, ["Produktivita"]);
  assert.deepEqual(
    result.map((item) => item.name).sort(),
    ["Energie", "Produktivita", "Únava"]
  );
  // The drifted range survived untouched.
  assert.equal(result.find((item) => item.name === "Energie")?.max_value, 10);
  // No deletion API is even reachable from the seeder.
  assert.equal(typeof (result as unknown as { delete?: unknown }).delete, "undefined");
});

test("a failed read rejects instead of seeding into an unknown account", async () => {
  let createCalls = 0;
  await assert.rejects(
    seedDefaultScales({
      read: async () => {
        throw new Error("network down");
      },
      create: async (def) => {
        createCalls += 1;
        return scale({ name: def.name });
      },
    }),
    /network down/
  );
  assert.equal(createCalls, 0);
});

test("an account with every default seeds nothing", async () => {
  const existing = DEFAULT_SCALE_DEFS.map((def) => scale({ ...def }));
  let createCalls = 0;
  const result = await seedDefaultScales({
    read: async () => existing,
    create: async (def) => {
      createCalls += 1;
      return scale({ name: def.name });
    },
  });
  assert.equal(createCalls, 0);
  assert.equal(result.length, existing.length);
});

test("an inactive default counts as existing and is not resurrected", async () => {
  const existing = [
    scale({ name: "Energie", is_active: false }),
    scale({ name: "Produktivita", is_active: false }),
  ];
  let createCalls = 0;
  await seedDefaultScales({
    read: async () => existing,
    create: async (def) => {
      createCalls += 1;
      return scale({ name: def.name });
    },
  });
  assert.equal(createCalls, 0);
});

test("one failed create keeps the scales that did succeed", async () => {
  const result = await seedDefaultScales({
    read: async () => [],
    create: async (def) => {
      if (def.name === "Produktivita") throw new Error("quota");
      return scale({ ...def });
    },
  });
  assert.deepEqual(result.map((item) => item.name), ["Energie"]);
});

test("missingDefaultScales matches by trimmed name only", () => {
  assert.deepEqual(
    missingDefaultScales([{ name: " Energie " }]).map((def) => def.name),
    ["Produktivita"]
  );
});
