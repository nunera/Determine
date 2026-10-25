<script lang="ts">
  import { onDestroy } from 'svelte';
  import type { Snippet } from 'svelte';
  import { createPresence, type PresenceState } from '../headless/presence';

  interface Tab { value: string; label: string; disabled?: boolean; content?: Snippet }
  interface Props { value?: string; tabs: Tab[]; orientation?: 'horizontal' | 'vertical'; activation?: 'automatic' | 'manual'; variant?: 'line' | 'enclosed' | 'pills'; onchange?: (value: string) => void }

  let { value = $bindable(), tabs, orientation = 'horizontal', activation = 'automatic', variant = 'line', onchange }: Props = $props();
  let focused = $state(-1);
  const instanceId = $props.id();

  const EXIT_DURATION = 120; // matches --br-motion-fast
  const presences = new Map<string, ReturnType<typeof createPresence>>();
  let panelState = $state<Record<string, PresenceState>>({});

  function presenceFor(tabValue: string) {
    let presence = presences.get(tabValue);
    if (presence) return presence;
    presence = createPresence(value === tabValue, { exitDuration: EXIT_DURATION });
    presences.set(tabValue, presence);
    panelState[tabValue] = presence.state;
    presence.subscribe(() => {
      panelState[tabValue] = presence!.state;
      if (presence!.state === 'exited') {
        const next = { ...panelState };
        delete next[tabValue];
        panelState = next;
      }
    });
    return presence;
  }

  $effect(() => {
    for (const tab of tabs) presenceFor(tab.value).set(value === tab.value);
  });

  onDestroy(() => { for (const presence of presences.values()) presence.destroy(); });

  function mountedFor(tabValue: string) {
    const state = panelState[tabValue];
    return state !== undefined && state !== 'exited';
  }

  $effect(() => {
    const selected = tabs.findIndex((tab) => tab.value === value && !tab.disabled);
    const firstEnabled = tabs.findIndex((tab) => !tab.disabled);
    focused = selected >= 0 ? selected : firstEnabled;
    if (selected < 0 && firstEnabled >= 0) value = tabs[firstEnabled].value;
  });

  function select(tab: Tab | undefined, index: number) {
    if (!tab || tab.disabled) return;
    focused = index;
    value = tab.value;
    onchange?.(tab.value);
  }

  function nextEnabled(start: number, direction: 1 | -1): number {
    for (let offset = 1; offset <= tabs.length; offset += 1) {
      const index = (start + direction * offset + tabs.length) % tabs.length;
      if (!tabs[index]?.disabled) return index;
    }
    return -1;
  }

  function keydown(event: KeyboardEvent) {
    const forward = orientation === 'horizontal' ? event.key === 'ArrowRight' : event.key === 'ArrowDown';
    const backward = orientation === 'horizontal' ? event.key === 'ArrowLeft' : event.key === 'ArrowUp';
    if ((event.key === 'Enter' || event.key === ' ') && activation === 'manual') {
      event.preventDefault();
      select(tabs[focused], focused);
      return;
    }
    if (!forward && !backward && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();

    const enabled = tabs.map((tab, index) => ({ tab, index })).filter(({ tab }) => !tab.disabled);
    const next = event.key === 'Home'
      ? enabled[0]?.index ?? -1
      : event.key === 'End'
        ? enabled.at(-1)?.index ?? -1
        : nextEnabled(focused, forward ? 1 : -1);
    if (next < 0) return;

    focused = next;
    document.getElementById(`${instanceId}-tab-${tabs[next].value}`)?.focus();
    if (activation === 'automatic') select(tabs[next], next);
  }
</script>

<div class="br-tabs" data-orientation={orientation} data-variant={variant}>
  <div class="br-tabs__list" role="tablist" aria-orientation={orientation} tabindex="-1" onkeydown={keydown}>
    {#each tabs as tab, index (tab.value)}
      <button
        id={`${instanceId}-tab-${tab.value}`}
        class="br-tab"
        role="tab"
        aria-selected={value === tab.value}
        aria-controls={`${instanceId}-panel-${tab.value}`}
        disabled={tab.disabled}
        tabindex={focused === index ? 0 : -1}
        onclick={() => select(tab, index)}
      >{tab.label}</button>
    {/each}
  </div>
  {#each tabs as tab (tab.value)}
    {#if mountedFor(tab.value)}
      <div id={`${instanceId}-panel-${tab.value}`} class="br-tabs__panel" data-state={panelState[tab.value]} role="tabpanel" aria-labelledby={`${instanceId}-tab-${tab.value}`}>
        {@render tab.content?.()}
      </div>
    {/if}
  {/each}
</div>

<style>
  .br-tabs[data-variant='enclosed'] .br-tab[aria-selected='true'] { border: 1px solid var(--br-lineHot); background: var(--br-panel); }
  .br-tabs[data-variant='pills'] .br-tab[aria-selected='true'] { border-radius: var(--br-radius-full); background: var(--br-structure); color: var(--br-on-structure); }
  .br-tabs__panel[data-state='entered'] { animation: br-tabs-panel-in var(--br-motion-fast) var(--br-ease-entrance); }
  .br-tabs__panel[data-state='exiting'] { animation: br-tabs-panel-in var(--br-motion-fast) var(--br-ease-exit) reverse; }
  @keyframes br-tabs-panel-in {
    from { opacity: 0; }
    to { opacity: 1; }
  }
</style>
