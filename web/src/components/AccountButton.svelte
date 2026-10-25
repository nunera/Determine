<script lang="ts">
  import { onMount } from "svelte";
  import { accountSession, refreshSession } from "../auth";

  let { onopen }: { onopen: () => void } = $props();
  const name = $derived(
    $accountSession.user?.name?.trim().split(/\s+/)[0] || "Account",
  );

  onMount(() => {
    const refresh = () => {
      void refreshSession().catch(() => {});
    };
    refresh();
    window.addEventListener("focus", refresh);
    window.addEventListener("pageshow", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      window.removeEventListener("pageshow", refresh);
    };
  });
</script>

<button
  type="button"
  class="account-link"
  onclick={onopen}
  aria-haspopup="dialog"
  aria-label={$accountSession.user
    ? `Open account for ${$accountSession.user.name || $accountSession.user.email}`
    : "Sign in"}
  aria-busy={$accountSession.pending}
  >{$accountSession.user ? name : "Sign in"}</button
>

<style>
  .account-link {
    border: 0;
    background: none;
    padding: 10px 0;
    color: var(--muted);
    font: inherit;
    font-size: 12px;
    white-space: nowrap;
    cursor: pointer;
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .account-link:hover {
    color: var(--ink);
  }
  .account-link:focus-visible {
    outline: 2px solid var(--blue);
    outline-offset: 4px;
    border-radius: 3px;
  }
</style>
