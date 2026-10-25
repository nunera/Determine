export type PresenceState = 'entered' | 'exiting' | 'exited';
export interface PresenceOptions { exitDuration?: number }

export function createPresence(initial = false, options: PresenceOptions = {}) {
  let mounted = initial;
  let state: PresenceState = initial ? 'entered' : 'exited';
  let timer: ReturnType<typeof setTimeout> | undefined;
  const listeners = new Set<() => void>();

  function notify() { for (const listener of listeners) listener(); }
  function set(visible: boolean) {
    clearTimeout(timer);
    if (visible) {
      mounted = true;
      state = 'entered';
      notify();
      return;
    }
    if (!mounted) return;
    state = 'exiting';
    notify();
    timer = setTimeout(() => {
      mounted = false;
      state = 'exited';
      notify();
    }, options.exitDuration ?? 0);
  }

  return {
    get mounted() { return mounted; },
    get state() { return state; },
    set,
    subscribe(listener: () => void) { listeners.add(listener); return () => listeners.delete(listener); },
    destroy() { clearTimeout(timer); listeners.clear(); }
  };
}
