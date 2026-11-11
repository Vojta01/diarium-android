import type { DraftLockManager } from "../src/lib/checkin/draft.ts";

interface LockGate {
  entered(): void;
  wait: Promise<void>;
  release(): void;
}

/** A process-wide Web Locks stand-in with real FIFO exclusion for unit tests. */
export class TestDraftLocks implements DraftLockManager {
  private readonly tails = new Map<string, Promise<void>>();
  private readonly gates = new Map<string, LockGate>();

  holdNext(name: string): { entered: Promise<void>; release(): void } {
    let signalEntered!: () => void;
    let release!: () => void;
    const entered = new Promise<void>((resolve) => {
      signalEntered = resolve;
    });
    const wait = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.gates.set(name, { entered: signalEntered, wait, release });
    return { entered, release };
  }

  async request<T>(name: string, callback: () => T): Promise<T> {
    const previous = this.tails.get(name) ?? Promise.resolve();
    let unlock!: () => void;
    const current = new Promise<void>((resolve) => {
      unlock = resolve;
    });
    this.tails.set(name, current);
    await previous;
    try {
      const gate = this.gates.get(name);
      if (gate) {
        this.gates.delete(name);
        gate.entered();
        await gate.wait;
      }
      return await callback();
    } finally {
      unlock();
      if (this.tails.get(name) === current) this.tails.delete(name);
    }
  }
}

export function testDraftLockResolver(locks: TestDraftLocks) {
  return () => locks;
}
