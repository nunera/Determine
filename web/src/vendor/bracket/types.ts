import type { Snippet } from 'svelte';

export type Size = 'xs' | 'sm' | 'md' | 'lg';
export type Intent = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'reference';
export type Variant = 'solid' | 'outline' | 'soft' | 'ghost' | 'link';
export type Orientation = 'horizontal' | 'vertical';
export type Density = 'compact' | 'comfortable' | 'spacious';
export type Align = 'start' | 'center' | 'end' | 'stretch' | 'baseline';
export type Justify = 'start' | 'center' | 'end' | 'between' | 'around' | 'evenly';
export type Child = Snippet | undefined;

export interface Option<T = string> {
  value: T;
  label: string;
  description?: string;
  disabled?: boolean;
  group?: string;
  icon?: string;
}

export interface Column<T> {
  key: keyof T & string;
  label: string;
  numeric?: boolean;
  sortable?: boolean;
  width?: string;
  render?: Snippet<[T]>;
}
