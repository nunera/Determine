<script lang="ts">
  export interface SliderMark { value: number; label: string }
  interface Props { value?: number | [number, number]; min?: number; max?: number; step?: number; label?: string; unit?: string; disabled?: boolean; name?: string; orientation?: 'horizontal' | 'vertical'; marks?: SliderMark[]; showTooltip?: boolean; onchange?: (value: number | [number, number]) => void }
  let { value = $bindable(50), min = 0, max = 100, step = 1, label = 'Value', unit = '', disabled = false, name, orientation = 'horizontal', marks = [], showTooltip = false, onchange }: Props = $props();
  const values = $derived(Array.isArray(value) ? value : [value]);
  const percent = (item: number) => ((item - min) / (max - min)) * 100;
  function set(index: number, next: number) { const bounded = Math.min(max, Math.max(min, next)); if (Array.isArray(value)) { const result: [number, number] = [...value]; result[index] = index === 0 ? Math.min(bounded, result[1]) : Math.max(bounded, result[0]); value = result; } else value = bounded; onchange?.(value); }
  function keydown(event: KeyboardEvent, index: number) { if (!['ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowDown'].includes(event.key)) return; event.preventDefault(); set(index, values[index] + (['ArrowRight', 'ArrowUp'].includes(event.key) ? step : -step)); }
</script>

<div class="br-slider" data-orientation={orientation} aria-label={label}>
  <div class="track">
    {#each values as item, index (index)}
      <!-- Apply native range bounds before value to prevent initial clamping. -->
      <input type="range" {min} {max} {step} value={item} {disabled} name={index === 0 ? name : undefined} aria-label={values.length === 2 ? `${label} ${index === 0 ? 'minimum' : 'maximum'}` : label} oninput={(event) => set(index, event.currentTarget.valueAsNumber)} onkeydown={(event) => keydown(event, index)} />
      {#if showTooltip}<output style:--position={`${percent(item)}%`}>{item}{unit}</output>{/if}
    {/each}
  </div>
  {#if marks.length}<div class="marks">{#each marks as mark (mark.value)}<span style:--position={`${percent(mark.value)}%`}>{mark.label}</span>{/each}</div>{/if}
</div>

<style>
  .br-slider { display: grid; gap: 5px; }
  .track { position: relative; display: grid; align-items: center; min-width: 140px; min-height: 30px; }
  input { grid-area: 1 / 1; width: 100%; accent-color: var(--br-signal); }
  output { position: absolute; inset-inline-start: var(--position); inset-block-end: 24px; translate: -50% 0; color: var(--br-signal); font-size: 10px; }
  .marks { position: relative; height: 16px; }
  .marks span { position: absolute; inset-inline-start: var(--position); translate: -50% 0; color: var(--br-mute); font-size: 9px; }
  .br-slider[data-orientation='vertical'] { display: inline-grid; grid-template-columns: 32px 40px; min-height: 160px; }
  .br-slider[data-orientation='vertical'] .track { width: 32px; min-width: 0; min-height: 160px; }
  .br-slider[data-orientation='vertical'] input { width: 160px; rotate: -90deg; }
  .br-slider[data-orientation='vertical'] .marks { height: 160px; }
</style>
