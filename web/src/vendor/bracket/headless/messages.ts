import { getContext, setContext } from 'svelte';

export interface BracketMessages {
  clearInput: string;
  increase: string;
  decrease: string;
  showPassword: string;
  hidePassword: string;
  passwordStrength: string;
  searchPlaceholder: string;
  searching: string;
  addTagPlaceholder: string;
  removeTag: (values: { tag: string }) => string;
  chooseFiles: string;
  fileUploadRegion: string;
  dropFilesHere: string;
  browseHint: (values: { maxSizeMiB: number; maxFiles: number }) => string;
  removeFile: (values: { name: string }) => string;
  optional: string;
  paginationNav: string;
  previousPage: string;
  nextPage: string;
  page: (values: { page: number }) => string;
  sortAscending: (values: { column: string }) => string;
  sortDescending: (values: { column: string }) => string;
  resizeColumn: (values: { column: string }) => string;
  loading: string;
  noRows: string;
  breadcrumbTrail: string;
  previousMonth: string;
  nextMonth: string;
  progressLabel: (values: { percent: number }) => string;
  syncing: string;
  copyCode: string;
  copied: string;
}

export const defaultMessages: BracketMessages = {
  clearInput: 'Clear input',
  increase: 'Increase',
  decrease: 'Decrease',
  showPassword: 'Show',
  hidePassword: 'Hide',
  passwordStrength: 'Password strength',
  searchPlaceholder: 'Search…',
  searching: 'Searching',
  addTagPlaceholder: 'Add tag…',
  removeTag: (values) => `Remove ${values.tag}`,
  chooseFiles: 'Choose files',
  fileUploadRegion: 'File upload',
  dropFilesHere: 'Drop files here',
  browseHint: (values) => `or browse · ${values.maxSizeMiB} MiB max · ${values.maxFiles} files`,
  removeFile: (values) => `Remove ${values.name}`,
  optional: 'optional',
  paginationNav: 'Pagination',
  previousPage: 'Previous page',
  nextPage: 'Next page',
  page: (values) => `Page ${values.page}`,
  sortAscending: (values) => `Sort ${values.column} ascending`,
  sortDescending: (values) => `Sort ${values.column} descending`,
  resizeColumn: (values) => `Resize ${values.column} column`,
  loading: 'Loading…',
  noRows: 'No rows',
  breadcrumbTrail: 'Breadcrumb',
  previousMonth: 'Previous month',
  nextMonth: 'Next month',
  progressLabel: (values) => `${values.percent}% complete`,
  syncing: 'syncing…',
  copyCode: 'Copy code',
  copied: 'Copied'
};

const MESSAGES_KEY = Symbol('bracket-messages');

export function setMessages(messages: Partial<BracketMessages>): void {
  setContext(MESSAGES_KEY, messages);
}

export function getMessages(): BracketMessages {
  const overrides = getContext<Partial<BracketMessages> | undefined>(MESSAGES_KEY);
  if (!overrides) return defaultMessages;
  const merged = {} as BracketMessages;
  for (const key of Object.keys(defaultMessages) as (keyof BracketMessages)[]) {
    (merged[key] as unknown) = overrides[key] ?? defaultMessages[key];
  }
  return merged;
}
