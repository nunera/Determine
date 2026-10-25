<script lang="ts">
  import { onMount } from "svelte";

  let { paused = false }: { paused?: boolean } = $props();
  const uid = $props.id();
  const chapters = [
    {
      image: "slim_hero",
      caption: "A closer look",
      alt: "Three-quarter CAD view of the transparent Determine glasses concept.",
      background: "#a7afb4",
    },
    {
      image: "slim_front",
      caption: "Built around the frame",
      alt: "Front CAD view showing the concept's clear frame and paired lens modules.",
      background: "#222327",
    },
    {
      image: "slim_exploded",
      caption: "Inside the idea",
      alt: "Exploded CAD view with the concept's temple covers and lens layers separated.",
      background: "#a7afb4",
    },
  ];
  let host: HTMLElement;
  let stage: HTMLDivElement;
  let progress = $state(0);
  let reducedMotion = $state(false);
  const view = $derived(progress * (chapters.length - 1));
  const active = $derived(Math.round(view));
  const still = $derived(paused || reducedMotion);
  const clamp = (value: number) => Math.max(0, Math.min(1, value));

  function opacity(index: number) {
    if (still) return active === index ? 1 : 0;
    // Incoming images dissolve over an opaque previous image, never through a blank backdrop.
    return index === 0 ? 1 : clamp(view - index + 1);
  }
  function selectChapter(index: number) {
    const travel = Math.max(1, host.offsetHeight - stage.offsetHeight);
    const top =
      window.scrollY +
      host.getBoundingClientRect().top -
      48 +
      (travel * index) / (chapters.length - 1);
    window.scrollTo({ top, behavior: still ? "instant" : "smooth" });
  }

  onMount(() => {
    const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onMotionChange = () => {
      reducedMotion = motion.matches;
    };
    onMotionChange();
    motion.addEventListener("change", onMotionChange);
    let frame = 0;
    let nearby = false;
    const measure = () => {
      frame = 0;
      const rect = host.getBoundingClientRect();
      progress = clamp(
        (48 - rect.top) / Math.max(1, rect.height - stage.offsetHeight),
      );
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(measure);
    };
    const onScroll = () => {
      if (nearby) schedule();
    };
    const observer = new IntersectionObserver(
      ([entry]) => {
        nearby = entry.isIntersecting;
        if (nearby) schedule();
      },
      { rootMargin: "100% 0px" },
    );
    observer.observe(host);
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", schedule, { passive: true });
    schedule();
    return () => {
      observer.disconnect();
      motion.removeEventListener("change", onMotionChange);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", schedule);
      cancelAnimationFrame(frame);
    };
  });
</script>

<section
  class="product-story"
  aria-label="Explore the frame design"
  bind:this={host}
>
  <div class="story-stage" bind:this={stage} data-testid="product-story-stage">
    {#each chapters as chapter, index}
      <figure
        id={`${uid}-frame-${index}`}
        class="story-frame"
        data-view={index}
        aria-hidden={active !== index}
        style:opacity={opacity(index)}
        style:background={chapter.background}
      >
        <img
          class="story-image"
          src={`/story/${chapter.image}.webp`}
          alt={chapter.alt}
          width="1600"
          height="1100"
          fetchpriority={index === 0 ? "high" : "auto"}
          decoding="async"
          style:transform={still
            ? "none"
            : `scale(${1 + 0.025 * (1 - clamp(view - index))})`}
        />
      </figure>
    {/each}
    <header
      class="story-caption"
      style:color={active === 1 ? "#f5f5f7" : "#171c20"}
    >
      <span>CAD design study</span>
      <h2>{chapters[active].caption}</h2>
    </header>
    <div class="story-controls">
      <span class="view-count" aria-hidden="true">0{active + 1} / 03</span>
      <nav aria-label="Choose a frame view">
        {#each chapters as chapter, index}
          <button
            type="button"
            aria-label={`Show image ${index + 1} of 3: ${chapter.caption}`}
            aria-pressed={active === index}
            aria-controls={`${uid}-frame-${index}`}
            onclick={() => selectChapter(index)}
            ><span aria-hidden="true">0{index + 1}</span></button
          >
        {/each}
      </nav>
    </div>
  </div>
</section>

<style>
  .product-story {
    position: relative;
    height: 260svh;
    color: var(--ink);
  }
  .story-stage {
    position: sticky;
    top: 48px;
    height: calc(100svh - 48px);
    overflow: hidden;
    isolation: isolate;
  }
  .story-frame {
    position: absolute;
    inset: 0;
    margin: 0;
    pointer-events: none;
    will-change: opacity;
  }
  .story-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
  .story-caption {
    position: absolute;
    inset: clamp(28px, 4vw, 58px) max(32px, calc((100vw - 1280px) / 2));
    bottom: auto;
    pointer-events: none;
  }
  .story-caption > span {
    font: 400 10px/1.5 var(--mono);
    opacity: 0.68;
  }
  h2 {
    margin: 12px 0 0;
    max-width: 640px;
    font: 600 clamp(32px, 4.4vw, 64px)/1.05 var(--display);
    letter-spacing: -0.05em;
  }
  .story-controls {
    position: absolute;
    bottom: 24px;
    left: max(32px, calc((100vw - 1280px) / 2));
    right: max(32px, calc((100vw - 1280px) / 2));
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
  }
  .view-count,
  nav {
    background: color-mix(in srgb, var(--paper) 70%, transparent);
    backdrop-filter: blur(14px);
    border: 1px solid color-mix(in srgb, var(--line) 50%, transparent);
    border-radius: 99px;
  }
  .view-count {
    padding: 13px 17px;
    font: 400 10px/1.5 var(--mono);
    font-variant-numeric: tabular-nums;
  }
  nav {
    display: flex;
    padding: 2px 5px;
  }
  button {
    position: relative;
    display: grid;
    place-items: center;
    width: 44px;
    height: 40px;
    padding: 0;
    background: none;
    border: 0;
    color: var(--muted);
    font: 400 10px/1 var(--mono);
    cursor: pointer;
    touch-action: manipulation;
  }
  button::after {
    content: "";
    position: absolute;
    bottom: 7px;
    left: 14px;
    right: 14px;
    height: 1px;
    background: currentColor;
    opacity: 0;
  }
  button[aria-pressed="true"],
  button:hover {
    color: var(--ink);
  }
  button[aria-pressed="true"]::after {
    opacity: 1;
  }
  button:focus-visible {
    outline: 2px solid var(--blue);
    outline-offset: -2px;
    border-radius: 20px;
  }
  @media (max-width: 640px) {
    .product-story {
      height: 230svh;
    }
    .story-caption {
      inset: 32px 24px auto;
    }
    h2 {
      max-width: 300px;
      font-size: clamp(32px, 8vw, 46px);
    }
    .story-controls {
      left: 20px;
      right: 20px;
      bottom: 20px;
    }
  }
</style>
