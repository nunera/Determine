<script lang="ts">
  let { paused = false }: { paused?: boolean } = $props();
</script>

<section
  id="technology"
  class="awareness"
  class:paused
  aria-labelledby="awareness-heading"
>
  <header class="awareness-heading">
    <h2 id="awareness-heading">
      Complementary<br /><span>by Design</span>
    </h2>
  </header>

  <figure>
    <figcaption class="awareness-summary">
      <p>Your cane below</p>
      <p>Determine ahead</p>
    </figcaption>
    <div class="scene">
      <svg
        class="awareness-drawing"
        viewBox="0 0 900 470"
        role="img"
        aria-labelledby="awareness-image-title awareness-image-description"
      >
        <title id="awareness-image-title"
          >Your cane below and Determine ahead</title
        >
        <desc id="awareness-image-description">
          A geometric side-profile pictogram of a person wearing Determine
          glasses and walking with a white cane. The cane touches the ground
          ahead, while a separate sensing field points from the glasses toward a
          head-level branch. Two small arcs beside the temple represent a felt
          haptic cue. This is a conceptual illustration, not a depiction of what
          a blind person sees or a measured sensing range. Optional camera
          descriptions are separate from the distance-to-haptic cue.
        </desc>

        <path class="ground" d="M60 442h780" />
        <path class="obstacle" d="M756 442V88m0 48H617" />
        <path class="branch" d="m688 136-24-23" />

        <g class="forward-sensing">
          <path class="sensing-field" d="m340 116 274-19v78Z" />
          <path class="sensing-edge" d="m340 116 274-19m-274 19 274 59" />
          <path class="sensing-center" d="m350 117 251 19" />
          <path
            class="sensor-accent"
            d="M449 109a112 112 0 0 1-2 31m70-36a181 181 0 0 1-4 55"
          />
          <circle class="obstacle-point" cx="614" cy="136" r="4" />
        </g>

        <ellipse class="sweep-area" cx="514" cy="442" rx="78" ry="13" />
        <path class="sweep-accent" d="M436 442c0 7 35 13 78 13s78-6 78-13" />

        <g class="person">
          <path
            class="legs"
            d="M266 311c-5 49-19 82-43 120m57-118c38 32 61 77 62 119"
          />
          <path class="body" d="M286 178c-13 36-19 77-19 116" />
          <path class="arm" d="m289 190 42 70h48" />
          <circle class="head" cx="300" cy="110" r="32" />
          <g class="glasses">
            <path d="M268 110h51" />
            <path class="lens" d="M319 103h17v18h-17Z" />
            <rect class="sensor" x="299" y="107" width="14" height="8" rx="2" />
          </g>
          <g class="felt-cue">
            <path d="M270 104a21 21 0 0 0-1 20m-9-25a32 32 0 0 0-1 30" />
          </g>
        </g>

        <g class="white-cane">
          <path class="cane-outline" d="m383 274 131 166" />
          <path class="cane-shaft" d="m383 274 131 166" />
          <path class="cane-grip" d="m379 269 10 13" />
          <path class="cane-tip" d="m512 437 4 6" />
        </g>

        <g class="callout-lines">
          <path d="M250 115h-78" />
          <circle cx="250" cy="115" r="2" />
        </g>
      </svg>
      <div class="scene-label label-cue"><p>A cue you feel</p></div>
    </div>
  </figure>
</section>

<style>
  .awareness {
    --cane-white: light-dark(var(--surface), var(--ink));
    width: min(1120px, calc(100% - 64px));
    margin: 0 auto;
    padding: clamp(72px, 10vw, 132px) 0 0;
    color: var(--ink);
    scroll-margin-top: 120px;
  }
  .awareness-heading {
    max-width: 770px;
  }
  h2 {
    margin: 0;
    font: 600 clamp(36px, 5.6vw, 68px)/1.04 var(--display);
    letter-spacing: -0.055em;
  }
  h2 span {
    color: var(--muted);
  }
  figure {
    margin: 32px 0 0;
    display: grid;
    grid-template-columns: 224px minmax(0, 1fr);
    gap: 28px;
    align-items: center;
  }
  .awareness-summary p {
    margin: 0;
    font: 500 clamp(19px, 2vw, 25px)/1.35 var(--display);
    letter-spacing: -0.035em;
  }
  .awareness-summary p + p {
    margin-top: 12px;
    color: var(--muted);
  }
  .scene {
    position: relative;
  }
  .awareness-drawing {
    display: block;
    width: 100%;
    height: auto;
  }
  .ground {
    stroke: var(--line);
    stroke-width: 1;
  }
  .obstacle,
  .branch {
    fill: none;
    stroke: var(--muted);
    stroke-width: 7;
    stroke-linecap: square;
  }
  .branch {
    stroke-width: 3;
  }
  .sensing-field {
    fill: var(--blue);
    opacity: 0.045;
  }
  .sensing-edge,
  .sensing-center,
  .sensor-accent {
    fill: none;
    stroke: var(--blue);
    stroke-width: 1;
    opacity: 0.35;
  }
  .sensing-center {
    stroke-dasharray: 2 7;
  }
  .sensor-accent {
    stroke-width: 1.5;
    animation: sensing-breathe 5s ease-in-out infinite;
  }
  .obstacle-point {
    fill: var(--paper);
    stroke: var(--blue);
    stroke-width: 1.5;
  }
  .sweep-area {
    fill: var(--blue);
    opacity: 0.05;
  }
  .sweep-accent {
    fill: none;
    stroke: var(--blue);
    stroke-width: 1.5;
    stroke-dasharray: 40 120;
    opacity: 0.5;
    animation: cane-sweep 6s ease-in-out infinite alternate;
  }
  .person,
  .white-cane {
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .body {
    stroke: var(--ink);
    stroke-width: 52;
  }
  .legs {
    stroke: var(--ink);
    stroke-width: 32;
  }
  .arm {
    stroke: var(--ink);
    stroke-width: 28;
  }
  .head {
    fill: var(--ink);
  }
  .glasses {
    stroke: var(--paper);
    stroke-width: 3;
  }
  .lens {
    fill: var(--paper);
    stroke: var(--ink);
    stroke-width: 2;
  }
  .sensor {
    fill: var(--blue);
    stroke: none;
  }
  .felt-cue {
    stroke: var(--blue);
    stroke-width: 1.5;
  }
  .cane-outline {
    stroke: var(--muted);
    stroke-width: 6;
  }
  .cane-shaft {
    stroke: var(--cane-white);
    stroke-width: 3.5;
  }
  .cane-grip,
  .cane-tip {
    stroke: var(--ink);
    stroke-width: 6;
  }
  .callout-lines {
    fill: none;
    stroke: var(--muted);
    stroke-width: 0.8;
    opacity: 0.6;
  }
  .callout-lines circle {
    fill: var(--paper);
  }
  .scene-label {
    position: absolute;
  }
  .scene-label p {
    margin: 0;
    font: 500 clamp(14px, 1.7vw, 18px)/1.3 var(--display);
    letter-spacing: -0.025em;
  }
  .label-cue {
    left: 2%;
    top: 22%;
  }
  @keyframes cane-sweep {
    from {
      stroke-dashoffset: -20;
    }
    to {
      stroke-dashoffset: -100;
    }
  }
  @keyframes sensing-breathe {
    0%,
    100% {
      opacity: 0.25;
    }
    50% {
      opacity: 0.6;
    }
  }
  .paused .sensor-accent,
  .paused .sweep-accent {
    animation: none;
  }
  @media (max-width: 640px) {
    .awareness {
      width: calc(100% - 40px);
      padding: 72px 0 0;
    }
    figure {
      margin-top: 28px;
      grid-template-columns: minmax(0, 1fr);
      gap: 24px;
    }
    .awareness-summary {
      display: flex;
      justify-content: space-between;
      gap: 16px;
    }
    .awareness-summary p {
      font-size: 17px;
    }
    .awareness-summary p + p {
      margin-top: 0;
    }
    .scene {
      display: grid;
      gap: 12px;
    }
    .awareness-drawing {
      margin-bottom: 8px;
    }
    .callout-lines {
      display: none;
    }
    .scene-label {
      position: static;
    }
    .scene-label p {
      font-size: 16px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .sensor-accent,
    .sweep-accent {
      animation: none;
    }
  }
</style>
