<script lang="ts">
  import Slider from "../vendor/bracket/components/Slider.svelte";
  import Button from "../vendor/bracket/components/Button.svelte";
  let { paused = false }: { paused?: boolean } = $props();
  let value = $state<number | [number, number]>(1050);
  const distance = $derived(Array.isArray(value) ? value[0] : value);
  const band = $derived(
    distance < 500 ? "Close" : distance < 900 ? "Medium" : "Far",
  );
  const pulses = $derived(distance < 500 ? 12 : distance < 900 ? 7 : 4);
  const obstacleX = $derived(180 + (distance - 200) * 0.62);
</script>

<section
  id="experience"
  class="cue-experience"
  class:paused
  aria-labelledby="cue-title"
>
  <div
    class="cue-inner page-width"
    style={`--cue-fill:${(distance - 200) / 10}%;--tempo:${distance < 500 ? 0.3 : distance < 900 ? 0.65 : 1.4}s`}
  >
    <header>
      <h2 id="cue-title">Distance & response</h2>
    </header>
    <div class="cue-readout">
      <h3 data-testid="cue-band">{band}</h3>
      <div class="measurement">
        <output data-testid="cue-distance"
          >{(distance / 1000).toFixed(2)}</output
        ><span>m ahead</span>
      </div>
    </div>
    <div class="cue-drawing" aria-hidden="true">
      <svg class="distance-plot" viewBox="0 0 960 150" fill="none">
        <path d="M70 64h820" class="guide" stroke-dasharray="2 8" />
        <path
          d={`M105 64H${obstacleX}M105 64l${obstacleX - 105} -32M105 64l${obstacleX - 105} 32`}
          class="sensing-line"
        />
        <path d={`M105 64L${obstacleX} 32v64Z`} class="sensing-fill" />
        <g class="glasses-glyph">
          <path d="M53 65v-5c0-4 3-6 7-6l33 7m9 2 4 1" />
          <path class="profile-lens" d="m92 59 11 2-2 13-8-2Z" />
          <rect
            class="profile-sensor"
            x="83"
            y="56"
            width="9"
            height="5"
            rx="1.5"
          />
        </g>
        <g transform={`translate(${obstacleX},0)`} class="obstacle"
          ><path d="M0 23v82" /><path d="M-7 23H7M-7 105H7" /><circle
            cy="64"
            r="5"
          /></g
        >
        <path d="M20 127h870" class="guide" /><path
          d="M180 123v8M366 123v8M614 123v8M800 123v8"
          class="guide"
        />
      </svg>
      <svg class="cue-response" viewBox="0 0 960 56" fill="none">
        <path class="response-baseline" d="M20 28h920" />
        {#each Array(pulses) as _, i}
          <path
            class="response-pulse"
            style={`--i:${i}`}
            transform={`translate(${70 + (i * 820) / Math.max(1, pulses - 1)},0)`}
            d="M-9 28h4l2-12 3 24 3-24 3 12h4"
          />
        {/each}
      </svg>
    </div>
    <div class="cue-controls">
      <div class="control-label">
        <span>Move the obstacle</span><output>{distance} <span>mm</span></output
        >
      </div>
      <div class="range-control">
        <Slider
          bind:value
          min={200}
          max={1200}
          step={10}
          label="Obstacle distance"
          unit=" mm"
        />
      </div>
      <div
        class="band-selector"
        role="group"
        aria-label="Explore distance bands"
      >
        {#each [{ label: "Close", value: 350 }, { label: "Medium", value: 700 }, { label: "Far", value: 1050 }] as choice}<Button
            variant="ghost"
            aria-pressed={band === choice.label}
            onclick={() => (value = choice.value)}>{choice.label}</Button
          >{/each}
      </div>
    </div>
  </div>
</section>

<style>
  .cue-experience {
    padding: 72px 0 64px;
  }
  .cue-inner {
    max-width: 960px;
    padding-block: 28px;
    border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
    background: transparent;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 24px;
  }
  header h2 {
    font-size: 17px;
    letter-spacing: -0.025em;
    font-weight: 500;
  }
  .cue-readout {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: 30px;
    margin-top: 28px;
  }
  .cue-readout h3 {
    font-size: clamp(44px, 5.4vw, 72px);
    font-weight: 500;
    letter-spacing: -0.065em;
    line-height: 1;
  }
  .measurement {
    text-align: right;
    display: grid;
    gap: 1px;
    padding-bottom: 7px;
  }
  .measurement output {
    font-size: clamp(32px, 4vw, 52px);
    line-height: 1;
    letter-spacing: -0.055em;
    font-variant-numeric: tabular-nums;
    font-weight: 400;
  }
  .measurement > span {
    font-size: 13px;
    color: var(--muted);
  }
  .cue-drawing {
    margin-top: 16px;
  }
  .distance-plot {
    display: block;
    width: 100%;
    height: 120px;
    overflow: visible;
  }
  .guide {
    stroke: var(--ink);
    stroke-opacity: 0.12;
    stroke-width: 1;
  }
  .sensing-line {
    stroke: var(--blue);
    stroke-width: 1;
    stroke-opacity: 0.45;
  }
  .sensing-fill {
    fill: var(--blue);
    fill-opacity: 0.035;
  }
  .glasses-glyph {
    stroke: var(--ink);
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .profile-lens {
    fill: color-mix(in srgb, var(--blue) 8%, var(--paper));
  }
  .profile-sensor {
    fill: var(--ink);
    stroke: none;
  }
  .obstacle {
    stroke: var(--blue);
    stroke-width: 2;
  }
  .obstacle circle {
    fill: var(--blue);
  }
  .cue-response {
    display: block;
    width: 100%;
    height: 56px;
  }
  .response-baseline {
    stroke: var(--ink);
    stroke-opacity: 0.1;
  }
  .response-pulse {
    stroke: var(--blue);
    stroke-width: 1.5;
    stroke-linejoin: round;
    opacity: 0.65;
    animation: cue-pulse var(--tempo) ease-out infinite;
    animation-delay: calc(var(--i) * 0.06s);
  }
  .cue-controls {
    margin-top: 22px;
  }
  .control-label {
    display: flex;
    justify-content: space-between;
    color: var(--muted);
    font-size: 12px;
  }
  .control-label output {
    color: var(--ink);
    font-variant-numeric: tabular-nums;
  }
  .control-label output span {
    color: var(--muted);
  }
  .range-control {
    margin-top: 10px;
  }
  .range-control :global(input) {
    appearance: none;
    height: 44px;
    background: transparent;
    margin: 0;
    cursor: ew-resize;
  }
  .range-control :global(input::-webkit-slider-runnable-track) {
    height: 2px;
    background: linear-gradient(
      to right,
      var(--blue) var(--cue-fill),
      color-mix(in srgb, var(--ink) 16%, transparent) var(--cue-fill)
    );
    border-radius: 2px;
  }
  .range-control :global(input::-webkit-slider-thumb) {
    appearance: none;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 1px solid var(--blue);
    margin-top: -7px;
    background: color-mix(in srgb, var(--ink) 24%, var(--paper));
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--blue) 8%, transparent);
  }
  .range-control :global(input::-moz-range-track) {
    height: 2px;
    background: color-mix(in srgb, var(--ink) 16%, transparent);
    border-radius: 2px;
  }
  .range-control :global(input::-moz-range-progress) {
    height: 2px;
    background: var(--blue);
  }
  .range-control :global(input::-moz-range-thumb) {
    width: 15px;
    height: 15px;
    border: 1px solid var(--blue);
    border-radius: 50%;
    background: color-mix(in srgb, var(--ink) 24%, var(--paper));
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--blue) 8%, transparent);
  }
  .band-selector {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    margin-top: 15px;
    border-bottom: 1px solid var(--line);
  }
  .band-selector :global(.br-btn) {
    min-height: 48px;
    font-size: 14px;
    border-radius: 0;
    border: 0;
    background: transparent;
    color: var(--muted);
    box-shadow: none;
  }
  .band-selector :global(.br-btn:hover) {
    color: var(--ink);
    background: transparent;
  }
  .band-selector :global(.br-btn[aria-pressed="true"]) {
    color: var(--blue);
    box-shadow: inset 0 -1px var(--blue);
  }
  .paused .response-pulse {
    animation-play-state: paused;
  }
  @keyframes cue-pulse {
    0%,
    100% {
      opacity: 0.5;
    }
    24% {
      opacity: 1;
    }
  }
  @media (max-width: 640px) {
    .cue-experience {
      padding: 44px 0;
    }
    header h2 {
      font-size: 16px;
    }
    .cue-readout {
      margin-top: 24px;
      gap: 16px;
    }
    .cue-readout h3 {
      font-size: clamp(42px, 12vw, 60px);
    }
    .measurement output {
      font-size: clamp(32px, 8vw, 44px);
    }
    .measurement > span {
      font-size: 10px;
    }
    .measurement {
      padding-bottom: 4px;
    }
    .cue-drawing {
      margin-top: 18px;
    }
    .distance-plot {
      height: auto;
      min-height: 95px;
    }
    .cue-controls {
      margin-top: 24px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .response-pulse {
      animation: none;
    }
  }
</style>
