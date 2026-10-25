<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';
  import type { Intent, Size, Variant } from '../types';
  import { getMessages } from '../headless/messages';

  interface Props extends HTMLButtonAttributes { children?: Snippet; leading?: Snippet; trailing?: Snippet; size?: Size; variant?: Variant; intent?: Intent; loading?: boolean; full?: boolean; href?: string; ref?: HTMLButtonElement | HTMLAnchorElement | null; onpress?: (event: MouseEvent) => void | Promise<void> }
  let { children, leading, trailing, size = 'md', variant = 'outline', intent = 'neutral', loading: loadingProp = false, full = false, href, ref = $bindable(null), onpress, disabled = false, type = 'button', onclick, ...rest }: Props = $props();
  let pending = $state(false);
  const loading = $derived(loadingProp || pending);
  const anchorRest = $derived(rest as unknown as HTMLAnchorAttributes);
  const messages = getMessages();
  async function activate(event: MouseEvent) {
    if (disabled || loading) { event.preventDefault(); event.stopPropagation(); return; }
    onclick?.(event as never);
    if (!onpress) return;
    const result = onpress(event);
    if (result instanceof Promise) { pending = true; try { await result; } finally { pending = false; } }
  }
</script>

{#snippet contents()}<span class="contents" style:visibility={loading ? 'hidden' : 'visible'}>{#if leading}{@render leading()}{/if}{@render children?.()}{#if trailing}{@render trailing()}{/if}</span>{#if loading}<span class="loader" role="status"><span class="br-sr">{messages.loading}</span>···</span>{/if}{/snippet}
{#if href}<a {...anchorRest} bind:this={ref} class="br-btn" data-size={size} data-variant={variant} data-intent={intent} data-full={full} aria-disabled={disabled || loading} {href} onclick={activate}>{@render contents()}</a>{:else}<button {...rest} bind:this={ref} class="br-btn" data-size={size} data-variant={variant} data-intent={intent} data-full={full} disabled={disabled || loading} aria-busy={loading} {type} onclick={activate}>{@render contents()}</button>{/if}
<style>.br-btn{position:relative;text-decoration:none}.contents{display:inline-flex;align-items:center;gap:1ch}.loader{position:absolute;inset:0;display:grid;place-items:center;animation:pulse 1s steps(2) infinite}@keyframes pulse{50%{opacity:.4}}</style>
