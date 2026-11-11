import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/platform/api/client";
import type { Configuration } from "./person-directory-contract";
import { directoryConfigurationState } from "./person-directory-rules";
export function usePersonDirectory(
  tenantId: string,
  authHint: string,
  canRead: boolean,
) {
  const client = useQueryClient();
  const identity = JSON.stringify([tenantId, authHint, canRead]);
  const prefix = ["finance", "person-directory", tenantId, authHint];
  const [denied, setDenied] = useState<string | null>(null);
  const query = useQuery({
    queryKey: [...prefix, "configuration"],
    enabled: !!tenantId && canRead && denied !== identity,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    queryFn: ({ signal }) =>
      api<Configuration>(
        `/finance/v1/tenants/${tenantId}/person-directory/configuration`,
        { signal },
      ),
  });
  useEffect(
    () => () => {
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    },
    [tenantId, authHint, client],
  );
  const onReadDenied = useCallback(() => {
    setDenied(identity);
    void client.cancelQueries({ queryKey: prefix });
    client.removeQueries({ queryKey: prefix });
  }, [tenantId, authHint, identity, client]);
  const retry = () => {
    setDenied(null);
    void query.refetch();
  };
  const readDenied =
    query.error instanceof ApiError && query.error.status === 403;
  useEffect(() => {
    if (readDenied) onReadDenied();
  }, [readDenied, onReadDenied]);
  const state = directoryConfigurationState({
    canRead,
    tenantId,
    configurationTenantId: query.data?.tenantId,
    denied: denied === identity,
    status: query.error instanceof ApiError ? query.error.status : undefined,
    fetching: query.isFetching,
    failed: query.isError,
  });
  return {
    configuration: state.visible ? query.data : undefined,
    error: query.error,
    denied: denied === identity,
    authorizationPending: state.authorizationPending,
    retry,
    onReadDenied,
  };
}
