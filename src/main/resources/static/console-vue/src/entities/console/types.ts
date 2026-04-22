export interface ConsoleMenuItem {
  label: string
  path: string
  permission?: string
}

export interface ConsoleBootstrapData {
  username: string
  permissions: string[]
  menus: ConsoleMenuItem[]
  featureFlags: Record<string, boolean>
}
