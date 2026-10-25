<script lang="ts">
  import { onMount, tick } from "svelte";
  import Button from "./vendor/bracket/components/Button.svelte";
  import GlassesScene from "./components/GlassesScene.svelte";
  import EngineerWorkspace from "./components/EngineerWorkspace.svelte";
  import ProfessionalWorkspace from "./components/ProfessionalWorkspace.svelte";
  import AuthDialog from "./components/AuthDialog.svelte";
  import AccountButton from "./components/AccountButton.svelte";
  import BrandMark from "./components/BrandMark.svelte";
  import CueExperience from "./components/CueExperience.svelte";
  import ProductScrollStory from "./components/ProductScrollStory.svelte";
  import AwarenessVisual from "./components/AwarenessVisual.svelte";

  type Theme = "paper" | "midnight" | "cobalt";
  type MenuId = "determine" | "simulation" | "engineers";
  let theme = $state<Theme>(
    (document.documentElement.dataset.theme as Theme) || "paper",
  );
  let studio = $state(
    location.hash === "#/studio" || location.hash === "#/studio/hitl",
  );
  let studioMode = $state<"sim" | "hitl">(
    location.hash === "#/studio/hitl" ? "hitl" : "sim",
  );
  let professional = $state(
    [
      "#/professionals",
      "#/professionals/api",
      "#/professionals/google",
    ].includes(location.hash),
  );
  const authQuery = new URLSearchParams(location.search);
  let signInOpen = $state(
    authQuery.has("auth_error") ||
      authQuery.has("error") ||
      authQuery.get("auth_action") === "reset" ||
      authQuery.has("auth_notice"),
  );
  let paused = $state(false);
  let menuOpen = $state(false);
  let activeMenu = $state<MenuId | null>(null);
  let navigationShell: HTMLDivElement;
  let menuTrigger: HTMLButtonElement | undefined;
  const navigation = [
    {
      id: "determine" as const,
      label: "Determine",
      heading: "Explore",
      links: [
        { label: "Overview", href: "#overview" },
        { label: "The experience", href: "#experience" },
      ],
      secondaryHeading: "A closer look",
      secondary: [
        { label: "Cane + glasses", href: "#technology" },
        { label: "Scene AI & APIs", href: "#/professionals/api" },
      ],
    },
    {
      id: "simulation" as const,
      label: "Simulation",
      heading: "Determine Studio",
      links: [
        { label: "Browser simulation", href: "#/studio" },
        { label: "Hardware in the loop", href: "#/studio/hitl" },
      ],
      secondaryHeading: "Live tools",
      secondary: [
        { label: "Explore a course", href: "#/studio" },
        { label: "Connect hardware", href: "#/studio/hitl" },
      ],
    },
    {
      id: "engineers" as const,
      label: "Professionals",
      heading: "For engineers & doctors",
      links: [
        { label: "Get started", href: "#/professionals" },
        { label: "Scene AI & compatibility", href: "#/professionals/api" },
      ],
      secondaryHeading: "Resources",
      secondary: [
        {
          label: "Project source",
          href: "https://github.com/nunera/Determine",
        },
        {
          label: "Simulation setup",
          href: "https://github.com/nunera/Determine/tree/main/tools/sim",
        },
      ],
    },
  ];
  const selectedMenu = $derived(
    navigation.find((item) => item.id === activeMenu),
  );
  function closeNavigation() {
    activeMenu = null;
    menuOpen = false;
  }
  function openMenu(id: MenuId, trigger: HTMLButtonElement) {
    activeMenu = id;
    menuTrigger = trigger;
  }
  function menuKey(event: KeyboardEvent, id: MenuId) {
    if (event.key !== "ArrowDown") return;
    event.preventDefault();
    openMenu(id, event.currentTarget as HTMLButtonElement);
    void tick().then(() =>
      document.querySelector<HTMLAnchorElement>(".mega-primary a")?.focus(),
    );
  }
  async function selectTheme(next: Theme) {
    if (next === theme) return;
    const apply = () => {
      theme = next;
      document.documentElement.dataset.theme = next;
      try {
        localStorage.setItem("determine-theme", next);
      } catch {
        /* The theme still works without storage. */
      }
    };
    if (
      document.startViewTransition &&
      !matchMedia("(prefers-reduced-motion: reduce)").matches
    )
      document.startViewTransition(async () => {
        apply();
        await tick();
      });
    else apply();
  }
  onMount(() => {
    const motion = matchMedia("(prefers-reduced-motion: reduce)");
    paused = motion.matches;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries)
          if (entry.isIntersecting) {
            entry.target.classList.add("visible");
            observer.unobserve(entry.target);
          }
      },
      { threshold: 0.08 },
    );
    const route = async () => {
      const nextStudio =
        location.hash === "#/studio" || location.hash === "#/studio/hitl";
      const nextMode = location.hash === "#/studio/hitl" ? "hitl" : "sim";
      const nextProfessional = [
        "#/professionals",
        "#/professionals/api",
        "#/professionals/google",
      ].includes(location.hash);
      const changed =
        nextStudio !== studio ||
        nextMode !== studioMode ||
        nextProfessional !== professional;
      studio = nextStudio;
      studioMode = nextMode;
      professional = nextProfessional;
      closeNavigation();
      document.title = studio
        ? "Determine — Live studio"
        : professional
          ? "Determine — Professional workspace"
          : "Determine — Sense what you can’t";
      await tick();
      observer.disconnect();
      document
        .querySelectorAll("[data-reveal]:not(.visible)")
        .forEach((element) => observer.observe(element));
      const section = document.getElementById(
        location.hash === "#/professionals/api"
          ? "professional-api"
          : location.hash === "#/professionals/google"
            ? "professional-google"
            : location.hash.slice(1),
      );
      if (section instanceof HTMLDetailsElement) section.open = true;
      if (section)
        section.scrollIntoView({
          behavior: changed || motion.matches ? "instant" : "smooth",
        });
      else if (changed || location.hash === "#/")
        window.scrollTo({ top: 0, behavior: "instant" });
    };
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && (activeMenu || menuOpen)) {
        closeNavigation();
        menuTrigger?.focus();
      }
    };
    const focusin = (event: FocusEvent) => {
      if (
        event.target instanceof Node &&
        !navigationShell.contains(event.target)
      )
        closeNavigation();
    };
    const scroll = () => {
      if (!menuOpen) activeMenu = null;
    };
    void route();
    window.addEventListener("hashchange", route);
    window.addEventListener("keydown", keydown);
    window.addEventListener("focusin", focusin);
    window.addEventListener("scroll", scroll, { passive: true });
    return () => {
      observer.disconnect();
      window.removeEventListener("hashchange", route);
      window.removeEventListener("keydown", keydown);
      window.removeEventListener("focusin", focusin);
      window.removeEventListener("scroll", scroll);
    };
  });
</script>

<a class="skip-link" href="#main">Skip to content</a>
<div
  class="global-nav-shell"
  role="presentation"
  class:mobile-open={menuOpen}
  bind:this={navigationShell}
  onpointerleave={() => {
    if (!menuOpen) activeMenu = null;
  }}
>
  <header class="site-header">
    <div class="nav-inner" class:has-flyout={!!activeMenu}>
      <a
        class="brand"
        href="#/"
        aria-label="Determine home"
        onclick={closeNavigation}><BrandMark /></a
      >
      <nav class:mobile-open={menuOpen} aria-label="Global navigation">
        {#each navigation as item}<button
            class:active={activeMenu === item.id}
            aria-expanded={activeMenu === item.id}
            aria-controls="global-flyout"
            onpointerenter={(event) => {
              if (
                event.pointerType === "mouse" &&
                matchMedia("(hover: hover)").matches
              )
                openMenu(item.id, event.currentTarget);
            }}
            onclick={(event) => {
              if (activeMenu === item.id) activeMenu = null;
              else openMenu(item.id, event.currentTarget);
            }}
            onkeydown={(event) => menuKey(event, item.id)}
            >{item.label}<span class="mobile-chevron" aria-hidden="true">›</span
            ></button
          >{/each}
      </nav>
      <div class="header-actions">
        <div class="theme-switcher" role="group" aria-label="Appearance">
          {#each ["paper", "midnight", "cobalt"] as option}<button
              class={`theme-dot ${option}`}
              aria-label={`${option[0].toUpperCase() + option.slice(1)} theme`}
              aria-pressed={theme === option}
              onclick={() => selectTheme(option as Theme)}><span></span></button
            >{/each}
        </div>
        <AccountButton
          onopen={() => {
            closeNavigation();
            signInOpen = true;
          }}
        /><button
          class="menu-toggle"
          aria-expanded={menuOpen}
          aria-label={menuOpen ? "Close navigation" : "Open navigation"}
          onclick={(event) => {
            menuTrigger = event.currentTarget;
            menuOpen = !menuOpen;
            activeMenu = null;
          }}><span class:cross={menuOpen}></span></button
        >
      </div>
    </div>
  </header>
  {#if selectedMenu}<div class="mega-panel" id="global-flyout">
      <div class="mega-inner">
        <button class="menu-back" onclick={() => (activeMenu = null)}
          >‹ All sections</button
        >
        <div class="mega-primary">
          <p>{selectedMenu.heading}</p>
          {#each selectedMenu.links as link}<a
              href={link.href}
              onclick={closeNavigation}>{link.label}</a
            >{/each}
        </div>
        <div class="mega-secondary">
          <p>{selectedMenu.secondaryHeading}</p>
          {#each selectedMenu.secondary as link}<a
              href={link.href}
              onclick={closeNavigation}>{link.label}</a
            >{/each}
        </div>
      </div>
    </div>{/if}
</div>
{#if activeMenu || menuOpen}<button
    class="nav-scrim"
    tabindex="-1"
    aria-label="Close navigation overlay"
    onclick={closeNavigation}
  ></button>{/if}
{#if studio}<div class="product-nav">
    <div class="product-nav-inner">
      <a class="product-nav-title" href="#/studio">Determine Studio</a>
      <nav aria-label="Studio navigation">
        <a
          href="#/studio"
          aria-current={studioMode === "sim" ? "page" : undefined}>Simulation</a
        ><a
          href="#/studio/hitl"
          aria-current={studioMode === "hitl" ? "page" : undefined}>Hardware</a
        >
        <a href="#/professionals">Professional setup</a>
      </nav>
    </div>
  </div>{/if}

<main id="main" class:motion-paused={paused && !studio && !professional}>
  {#if studio}<div class="studio-page page-width">
      {#key studioMode}<EngineerWorkspace initialMode={studioMode} />{/key}
    </div>
  {:else if professional}
    <div class="studio-page page-width"><ProfessionalWorkspace /></div>
  {:else}
    <div class="project-ribbon">
      <a href="#/studio">See it live <span aria-hidden="true">›</span></a>
    </div>
    <section id="overview" class="hero">
      <div class="hero-copy page-width">
        <h1>Sense what <span>you can’t</span></h1>
        <div class="hero-ctas">
          <Button href="#experience" variant="solid" intent="primary"
            >Explore Determine</Button
          ><Button href="#/studio" variant="outline" intent="primary"
            >Try the simulation</Button
          >
        </div>
      </div>
      <div class="product-stage">
        <GlassesScene {theme} {paused} /><button
          class="motion-toggle"
          aria-label={paused ? "Play motion" : "Pause motion"}
          title={paused ? "Play motion" : "Pause motion"}
          aria-pressed={paused}
          onclick={() => (paused = !paused)}
          ><span aria-hidden="true">{paused ? "▶" : "Ⅱ"}</span></button
        >
      </div>
    </section>
    <CueExperience {paused} />
    <ProductScrollStory {paused} />
    <AwarenessVisual {paused} />
    <section id="engineering" class="engineering page-width" data-reveal>
      <div class="studio-promo">
        <div class="studio-promo-copy">
          <p class="eyebrow">For professionals</p>
          <h2>Hardware<br /><span>in the loop.</span></h2>
          <p>
            A live workspace for engineers and doctors.<br />Connect. Configure.
            Explore.
          </p>
          <div class="promo-actions">
            <Button href="#/professionals" variant="solid" intent="primary"
              >Get started</Button
            ><a class="text-link" href="#/professionals/api"
              >APIs & compatibility <span aria-hidden="true">›</span></a
            >
          </div>
        </div>
        <a
          class="simulation-emblem"
          href="#/studio"
          aria-label="Open Determine Studio"
          ><svg viewBox="0 0 400 340" fill="none" aria-hidden="true"
            ><g stroke="var(--blue)"
              ><rect
                x="74"
                y="44"
                width="252"
                height="252"
                rx="66"
                opacity=".12"
              /><path
                d="M128 58H99a13 13 0 0 0-13 13v29M272 282h29a13 13 0 0 0 13-13v-29"
                opacity=".5"
              /><path d="M314 121v95M86 219v-95" opacity=".2" /><path
                d="m309 121 5-7 5 7M81 219l5 7 5-7"
                opacity=".5"
                stroke-linecap="round"
                stroke-linejoin="round"
              /></g
            ><svg
              x="126"
              y="96"
              width="148"
              height="148"
              viewBox="0 0 64 64"
              style="--determine-mark-fg:var(--ink)"
              ><use href="/favicon.svg#determine-mark" /></svg
            ></svg
          ><span>Determine Studio <span aria-hidden="true">↗</span></span></a
        >
      </div>
    </section>
  {/if}
</main>
<footer class="site-footer">
  <div class="page-width">
    <nav class="footer-links" aria-label="Footer">
      <div class="footer-brand">
        <a class="footer-wordmark" href="#/"
          ><BrandMark /><span>Determine</span></a
        >
        <p class="footer-tagline">Sense what you can’t</p>
      </div>
      <div>
        <h2>Explore</h2>
        <a href="#overview">Overview</a><a href="#experience">The experience</a
        ><a href="#technology">Cane + glasses</a>
      </div>
      <div>
        <h2>Professionals</h2>
        <a href="#/professionals">Get started</a><a href="#/studio"
          >Simulation</a
        ><a href="#/studio/hitl">Hardware in the loop</a><button
          onclick={() => (signInOpen = true)}>Your account</button
        >
      </div>
      <div>
        <h2>Project</h2>
        <a href="https://github.com/nunera/Determine">GitHub</a><a
          href="#/professionals/api">Scene AI & APIs</a
        >
      </div>
    </nav>
  </div>
</footer>
<AuthDialog
  bind:open={signInOpen}
  onguest={() => {
    signInOpen = false;
    if (!studio && !professional) location.hash = "#/studio";
  }}
/>
