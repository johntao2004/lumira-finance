export function yearWriteRestriction(
  year:
    | {
        status?: string;
        precisionProfile?: string;
        precisionReviewRequired?: boolean;
      }
    | undefined,
): string | null {
  if (!year) return "年度信息尚未确认，仅可查阅。";
  if (year.precisionProfile !== "V4" || year.precisionReviewRequired !== false)
    return "旧版精度历史，仅可查阅；需受控复核迁移后再处理";
  if (year.status === "OPEN") return null;
  if (year.status === "CLOSED_PENDING_ARCHIVE")
    return "结转已提交，待归档；本年度财务只读，归档尚未完成。";
  if (year.status === "CLOSED") return "本年度已关闭，财务只读。";
  if (year.status === "CLOSING") return "本年度正在结账，暂停财务写入。";
  return "年度状态尚未确认，仅可查阅。";
}
/** Projection recovery never authorizes financial changes or reopens a year. */
export function yearProjectionRestriction(
  year: Parameters<typeof yearWriteRestriction>[0],
): string | null {
  if (
    !year ||
    !["OPEN", "CLOSING", "CLOSED_PENDING_ARCHIVE", "CLOSED"].includes(
      year.status ?? "",
    )
  )
    return "年度信息或状态尚未确认，不能重试投影消费者。";
  if (year.precisionProfile !== "V4" || year.precisionReviewRequired !== false)
    return "旧版精度历史，受控复核迁移前不能重试投影消费者。";
  return null;
}
