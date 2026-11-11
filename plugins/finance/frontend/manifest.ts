import type { FrontendModuleManifest } from "@/platform/modules/types";
export const financeManifest = {
  id: "finance",
  version: "0.24.2",
  required: false,
  dependencies: ["platform", "identity"],
  routes: [
    {
      id: "finance-workbench",
      label: "financeWorkbench",
      permission: "platform:finance:access",
      operations: {
        read: "platform:finance:read",
        manage: "platform:finance:manage",
        create: "platform:finance:create",
        approve: "platform:finance:approve",
        post: "platform:finance:post",
        close: "platform:finance:close",
        export: "platform:finance:export",
        "person-read": "platform:finance:person-read",
        "person-propose": "platform:finance:person-propose",
        "person-confirm": "platform:finance:person-confirm",
      },
      accessScope: "scoped",
      scopedOperations: [
        "read",
        "manage",
        "create",
        "approve",
        "post",
        "close",
        "export",
        "person-read",
        "person-propose",
        "person-confirm",
      ],
      group: "overview",
      order: 20,
    },
  ],
} as const satisfies FrontendModuleManifest;

