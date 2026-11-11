import { lazy } from "react";
import type { FrontendModule } from "@/platform/modules/types";
import { financeManifest } from "./manifest";
const FinancePage = lazy(() =>
  import("./finance-page").then((m) => ({ default: m.FinancePage })),
);
export const financeModule = {
  ...financeManifest,
  pages: {
    "finance-workbench": (context) => (
      <FinancePage
        authorizationHint={JSON.stringify([
          context.readOnlyPreview === true,
          [...context.authorities].sort(),
        ])}
        permissions={Object.fromEntries(
          [
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
          ].map((operation) => [
            operation,
            context.hasOperation(operation) &&
              (operation === "read" ||
                operation === "person-read" ||
                !context.readOnlyPreview),
          ]),
        )}
      />
    ),
  },
} satisfies FrontendModule;
