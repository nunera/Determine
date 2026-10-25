<script lang="ts">
  import { onDestroy } from 'svelte';
  import type { Snippet } from 'svelte';
  import { createPresence } from '../headless/presence';

  interface Props {
    open?: boolean;
    title: string;
    description?: string;
    children: Snippet;
    footer?: Snippet;
    closeOnOutside?: boolean;
    closeOnEscape?: boolean;
    size?: 'sm' | 'md' | 'lg' | 'full';
    onclose?: () => void;
  }

  let {
    open = $bindable(false),
    title,
    description,
    children,
    footer,
    closeOnOutside = true,
    closeOnEscape = true,
    size = 'md',
    onclose
  }: Props = $props();
  let element = $state<HTMLDialogElement>();
  let trigger = $state<HTMLElement | null>(null);
  const instanceId = $props.id();
  const titleId = `${instanceId}-title`;

  const EXIT_DURATION = 220; // matches --br-motion-medium
  const presence = createPresence(open, { exitDuration: EXIT_DURATION });
  let mounted = $state(presence.mounted);
  let dataState = $state(presence.state);

  $effect(() => presence.subscribe(() => {
    mounted = presence.mounted;
    dataState = presence.state;
  }));
  $effect(() => { presence.set(open); });
  onDestroy(() => presence.destroy());

  $effect(() => {
    if (!element) return;
    if (open && !element.open) {
      trigger = document.activeElement as HTMLElement;
      element.showModal?.();
    }
  });

  $effect(() => {
    if (!element) return;
    if (!mounted && element.open) element.close?.();
  });

  function close() {
    open = false;
    onclose?.();
    queueMicrotask(() => trigger?.focus());
  }

  function keydown(event: KeyboardEvent) {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    if (closeOnEscape) close();
  }
</script>

<dialog
  bind:this={element}
  class="native"
  data-state={dataState}
  aria-labelledby={titleId}
  onclose={() => { if (open) close(); }}
  onkeydown={keydown}
  onclick={(event) => { if (closeOnOutside && event.target === element) close(); }}
>
  <div class="br-dialog" data-state={dataState} data-size={size}>
    <header class="br-dialog__header">
      <h2 id={titleId} class="br-dialog__title">{title}</h2>
      <span class="br-grow"></span>
      <button class="br-icon-btn" aria-label="Close dialog" onclick={close}>×</button>
    </header>
    {#if mounted}
      {#if description}<div class="description">{description}</div>{/if}
      <div class="br-dialog__body">{@render children()}</div>
      {#if footer}<footer class="br-dialog__footer">{@render footer()}</footer>{/if}
    {/if}
  </div>
</dialog>

<style>
  .native { max-width: none; max-height: none; width: 100vw; height: 100dvh; margin: 0; padding: 0; border: 0; background: transparent; color: inherit; }
  .native::backdrop { background: color-mix(in srgb, var(--br-void) 78%, transparent); transition: opacity var(--br-motion-medium) var(--br-ease-standard); }
  .native[data-state='exiting']::backdrop { opacity: 0; }
  .description { padding: 8px 12px 0; color: var(--br-mute); font-size: 11px; }
  .br-dialog[data-size='sm'] { width: min(380px, calc(100vw - 24px)); }
  .br-dialog[data-size='md'] { width: min(560px, calc(100vw - 24px)); }
  .br-dialog[data-size='lg'] { width: min(820px, calc(100vw - 24px)); }
  .br-dialog[data-size='full'] { width: calc(100vw - 24px); height: calc(100dvh - 24px); }
  .br-dialog[data-state='entered'] { animation: br-dialog-in var(--br-motion-medium) var(--br-ease-entrance); }
  .br-dialog[data-state='exiting'] { animation: br-dialog-in var(--br-motion-medium) var(--br-ease-exit) reverse; }
  @keyframes br-dialog-in {
    from { opacity: 0; transform: scale(.98) translateY(4px); }
    to { opacity: 1; transform: scale(1) translateY(0); }
  }
</style>
