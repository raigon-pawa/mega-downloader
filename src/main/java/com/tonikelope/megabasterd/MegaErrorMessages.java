/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Human-friendly mapping of MEGA API error codes to short name + meaning +
 * suggested action, plus a helper that reports (logs) an explanation of
 * what the user is looking at. Previously these errors surfaced as raw "FATAL
 * ERROR! MEGA API ERROR: -16" strings on row status labels (or silently as a
 * null quota) and the user had no idea whether the cause was MegaBasterd, the
 * link, the account, or MEGA's server.
 *
 * Many MEGA codes have DIFFERENT real-world meanings depending on whether the
 * failing call was account-related (login, getQuota, fetchNodes,
 * initUploadFile) or link/file-related (getMegaFileMetadata,
 * getMegaFileDownloadUrl). For example -9 (ENOENT) on a link means "file
 * deleted by the owner", but -9 on a login means "this email does not exist
 * on MEGA (or the stored credentials no longer match)". The {@link Source}
 * enum routes the message accordingly so the popup is actually useful.
 *
 * The mapping follows the codes documented at
 * https://github.com/meganz/sdk/blob/master/include/mega/error.h .
 *
 * @author tonikelope
 */
public final class MegaErrorMessages {

    /**
     * Which API call produced the error. Some MEGA codes (notably -9, -11,
     * -16) mean different things in account vs. link context, so callers
     * pass this so {@link #getMeaning} and {@link #getSuggestion} can return
     * the right text. (#751 / D follow-up)
     */
    public enum Source {
        /**
         * Account-level operation: login / fastLogin / getQuota / fetchNodes
         * / initUploadFile / etc. Errors here apply to a MEGA account.
         */
        ACCOUNT,
        /**
         * Link/file operation: getMegaFileMetadata / getMegaFileDownloadUrl.
         * Errors here apply to a specific public link or node.
         */
        LINK,
        /**
         * Caller doesn't know or it doesn't matter (e.g. transient codes
         * like -3 / -4). Returns the generic copy.
         */
        GENERIC
    }

    private MegaErrorMessages() {
    }

    /**
     * Dedup window: never show the same (code, identity) popup more than once
     * every 60 s. Avoids spamming the user when a refresh loop or a worker is
     * retrying.
     */
    private static final long POPUP_DEDUP_WINDOW_MS = 60_000L;
    private static final ConcurrentHashMap<String, Long> LAST_POPUP_TS = new ConcurrentHashMap<>();

    public static String getShortName(int code) {
        switch (code) {
            case -1:
                return "EINTERNAL";
            case -2:
                return "EARGS";
            case -3:
                return "EAGAIN";
            case -4:
                return "ERATELIMIT";
            case -5:
                return "EFAILED";
            case -6:
                return "ETOOMANY";
            case -7:
                return "ERANGE";
            case -8:
                return "EEXPIRED";
            case -9:
                return "ENOENT";
            case -10:
                return "ECIRCULAR";
            case -11:
                return "EACCESS";
            case -12:
                return "EEXIST";
            case -13:
                return "EINCOMPLETE";
            case -14:
                return "EKEY";
            case -15:
                return "ESID";
            case -16:
                return "EBLOCKED";
            case -17:
                return "EOVERQUOTA";
            case -18:
                return "ETEMPUNAVAIL";
            case -19:
                return "ETOOMANYCONNECTIONS";
            case -20:
                return "EWRITE";
            case -21:
                return "EREAD";
            case -22:
                return "EAPPKEY";
            case -23:
                return "ESSL";
            case -24:
                return "EGOINGOVERQUOTA";
            case -25:
                return "EMFAREQUIRED";
            case -26:
                return "EMASTERONLY";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Backwards-compatible meaning lookup (treats Source as GENERIC).
     */
    public static String getMeaning(int code) {
        return getMeaning(code, Source.GENERIC);
    }

    public static String getMeaning(int code, Source source) {
        switch (code) {
            case -1:
                return "Internal MEGA server error.";
            case -2:
                return "Bad arguments sent to MEGA (likely an internal MegaBasterd bug).";
            case -3:
                return "MEGA is asking us to retry later (server-side overload).";
            case -4:
                return "Too many requests to MEGA from your IP (rate limit).";
            case -5:
                return "MEGA reported a permanent failure for this request.";
            case -6:
                return "Too many concurrent IP addresses on this account.";
            case -7:
                return "Requested range is out of bounds for this resource.";
            case -8:
                if (source == Source.ACCOUNT) {
                    return "The MEGA account session has expired and is no longer valid.";
                }
                return "The MEGA link has expired and is no longer accessible.";
            case -9:
                if (source == Source.ACCOUNT) {
                    return "The MEGA account does not exist on MEGA's servers, or the stored credentials no longer match (for example, the password was changed on the web client). MEGA returns this code when the account it identifies cannot be resolved.";
                }
                return "The file or folder was not found on MEGA (may have been deleted by the owner).";
            case -10:
                return "Circular linkage detected.";
            case -11:
                if (source == Source.ACCOUNT) {
                    return "Access denied by MEGA: the credentials were accepted but the account is not allowed to perform this operation (it may be limited, frozen, or pending email confirmation).";
                }
                return "Access denied by MEGA: the link may require a specific account, may be private, or you don't have permission for this resource.";
            case -12:
                return "Resource already exists.";
            case -13:
                return "Operation incomplete.";
            case -14:
                if (source == Source.ACCOUNT) {
                    return "MEGA rejected the account's decryption key. This usually means the stored password no longer matches the one MEGA has on file -- the account password was changed elsewhere.";
                }
                return "Wrong password or invalid decryption key for this MEGA link.";
            case -15:
                return "Your MEGA session was invalidated (logged out remotely, expired, or the account was modified server-side).";
            case -16:
                if (source == Source.LINK) {
                    return "MEGA has BLOCKED this file or folder (typically a DMCA / Terms of Service takedown). It cannot be downloaded from any account. The block is enforced by MEGA's servers and is permanent.";
                }
                return "This MEGA account is currently BLOCKED by MEGA. The block can be either (a) a temporary SECURITY HOLD -- e.g. MEGA detected an unusual login attempt and froze the account until you confirm it; or (b) a permanent ACCOUNT BAN (Terms of Service violation, suspicious activity, throwaway-email detection). MegaBasterd cannot tell which from the API error alone -- the cause is only visible if you log into mega.nz in your browser. This is enforced by MEGA's servers, NOT by MegaBasterd.";
            case -17:
                return "This MEGA account is OVER its storage or bandwidth quota.";
            case -18:
                return "Resource temporarily not available; try again later.";
            case -19:
                return "Too many concurrent connections from your IP.";
            case -20:
                return "MEGA could not write to the file.";
            case -21:
                return "MEGA could not read from the file.";
            case -22:
                return "Invalid or missing application key.";
            case -23:
                return "SSL verification failed.";
            case -24:
                return "This action would push the account over its storage quota.";
            case -25:
                return "MEGA requires multi-factor authentication for this account.";
            case -26:
                return "This action requires being logged into a MEGA account.";
            default:
                return "Generic MEGA API error.";
        }
    }

    /**
     * Backwards-compatible suggestion lookup (treats Source as GENERIC).
     */
    public static String getSuggestion(int code) {
        return getSuggestion(code, Source.GENERIC);
    }

    public static String getSuggestion(int code, Source source) {
        switch (code) {
            case -1:
            case -3:
            case -5:
            case -18:
                return "Wait a few minutes and try again. If it persists, MEGA's servers may be having issues.";
            case -2:
                return "Verify the MEGA link is complete and correctly pasted. If you copied it from MegaBasterd, file a bug report.";
            case -4:
            case -19:
                return "You are sending too many requests. Reduce concurrent transfers, or wait a few minutes for the rate limit to clear.";
            case -6:
                return "MEGA blocks accounts being used from too many IPs at once. Close MegaBasterd on other machines, or use a different account.";
            case -8:
                if (source == Source.ACCOUNT) {
                    return "Re-add the account from Settings -> Accounts (the stored session has expired).";
                }
                return "The link has expired -- the owner needs to re-upload and share a new one.";
            case -9:
                if (source == Source.ACCOUNT) {
                    return "Verify the account email is correct. If the password was changed via mega.nz, re-add the account from Settings -> Accounts with the new password. If the account itself was deleted, remove it from MegaBasterd.";
                }
                return "Verify the link is correct. The file may have been deleted by its owner -- try with a different link.";
            case -11:
                if (source == Source.ACCOUNT) {
                    return "Log into the account on mega.nz to check its status -- common causes are an unconfirmed email or a usage limit. Once cleared on the web, retry from MegaBasterd.";
                }
                return "Verify the link is for a public resource. If you need an authenticated account, configure it in Settings -> Accounts.";
            case -14:
                if (source == Source.ACCOUNT) {
                    return "Re-add the account from Settings -> Accounts with the current password (the password stored in MegaBasterd no longer matches MEGA's records).";
                }
                return "Re-check the password used to decrypt this MEGA link.";
            case -15:
                return "Re-login the affected account from Settings -> Accounts.";
            case -16:
                if (source == Source.LINK) {
                    return "Try a different link to the same file (if available). Files removed via DMCA are not retrievable by any account.";
                }
                return "Open mega.nz in a browser and log into this account: the web client will show the actual cause. If it's a security hold you can usually clear it by following the on-screen prompts (re-confirm email, change password). If the account is permanently banned, try a different account.";
            case -17:
            case -24:
                return "Free up space in this MEGA account, or upload to a different one.";
            case -25:
                return "MegaBasterd will prompt for your 2FA code -- enter it when asked.";
            case -26:
                return "Log into the MEGA account from Settings -> Accounts before attempting this.";
            default:
                return "Retry the operation. If it persists, try a different account or a different link.";
        }
    }

    /**
     * Whether this code is worth interrupting the user with a popup, or if it
     * should just be logged. Mirrors {@link Transference#FATAL_API_ERROR_CODES}
     * -- any code outside that set is treated as transient (server overload,
     * temporary failure, rate limit, ...) and the popup is suppressed. The
     * caller is still free to surface a row-level status string. (#751 / D)
     */
    public static boolean isFatalForPopup(int code) {
        for (Integer fatal : Transference.FATAL_API_ERROR_CODES) {
            if (fatal == code) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAccountBlocked(int code) {
        return code == -16;
    }

    public static boolean isAccessDenied(int code) {
        return code == -11;
    }

    public static boolean isQuotaIssue(int code) {
        return code == -17 || code == -24 || code == -4;
    }

    public static boolean isSessionDead(int code) {
        return code == -15;
    }

    public static boolean isLinkBroken(int code) {
        return code == -8 || code == -9 || code == -14;
    }

    public static String getTitle(int code) {
        return getTitle(code, Source.GENERIC);
    }

    public static String getTitle(int code, Source source) {
        if (isAccountBlocked(code)) {
            return source == Source.LINK ? "MEGA file blocked / taken down" : "MEGA account blocked";
        }
        if (isQuotaIssue(code)) {
            return "MEGA quota issue";
        }
        if (isSessionDead(code)) {
            return "MEGA session invalid";
        }
        if (source == Source.ACCOUNT && (code == -9 || code == -14)) {
            return "MEGA account credentials problem";
        }
        if (isLinkBroken(code)) {
            return "MEGA link unavailable";
        }
        return "MEGA API error " + code;
    }

    /**
     * Backwards-compatible showPopup (treats Source as GENERIC).
     */
    public static void showPopup(int code, String identity, String context) {
        showPopup(code, identity, context, Source.GENERIC);
    }

    /**
     * Plain-text (markdown-ish) rendering of the same information shown in
     * the popup, formatted to drop straight into a GitHub issue. Includes
     * the MegaBasterd version so we know which build the report came from.
     */
    public static String buildPlainText(int code, String identity, String context, Source source) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(getTitle(code, source)).append("**\n\n");
        sb.append("- MegaBasterd version: ").append(MainPanel.VERSION).append("\n");
        sb.append("- Error code: ").append(code).append(" (").append(getShortName(code)).append(")\n");
        sb.append("- Source: ").append(source).append("\n");
        if (identity != null && !identity.isEmpty()) {
            sb.append("- Identity: `").append(identity).append("`\n");
        }
        if (context != null && !context.isEmpty()) {
            sb.append("- Context: ").append(context).append("\n");
        }
        sb.append("\n**What this means:**\n").append(getMeaning(code, source)).append("\n");
        sb.append("\n**What you can do:**\n").append(getSuggestion(code, source)).append("\n");
        return sb.toString();
    }

    /**
     * Report a fatal MEGA error: logs the plain-text explanation (no
     * window -- the engine is toolkit-free). Deduplicates so the same
     * (code, source, identity) isn't reported twice within
     * {@link #POPUP_DEDUP_WINDOW_MS}.
     *
     * @param code MEGA API error code (negative).
     * @param identity Account email / link / file name this error applies to.
     * @param context Free-text context. E.g. "while
     * checking account quota" or "during chunk upload".
     * @param source ACCOUNT, LINK or GENERIC -- routes the explanation for
     * codes whose meaning depends on the calling operation
     * (notably -8, -9, -11, -14, -16).
     */
    public static void showPopup(int code, String identity, String context, Source source) {
        // Popups are reserved for FATAL_API_ERROR_CODES. Transient codes (-3,
        // -5 EFAILED, -18 ETEMPUNAVAIL, ...) are handled automatically by the
        // retry/backoff machinery; popping a dialog for them is just noise.
        // Anything not in the fatal set is logged and dropped.
        if (!isFatalForPopup(code)) {
            java.util.logging.Logger.getLogger(MegaErrorMessages.class.getName())
                    .log(java.util.logging.Level.FINE,
                            "Suppressing non-fatal MEGA popup: code={0} identity={1} context={2}",
                            new Object[]{code, identity, context});
            return;
        }

        // Dedup also keys on source: showing two popups for the same code on
        // the same identity but from different operations would be noise.
        String dedup_key = code + "|" + source + "|" + (identity == null ? "" : identity);
        long now = System.currentTimeMillis();
        Long prev = LAST_POPUP_TS.get(dedup_key);
        if (prev != null && now - prev < POPUP_DEDUP_WINDOW_MS) {
            return;
        }
        LAST_POPUP_TS.put(dedup_key, now);

        final boolean is_error = isAccountBlocked(code) || (source == Source.LINK && isLinkBroken(code));

        java.util.logging.Logger.getLogger(MegaErrorMessages.class.getName())
                .log(is_error ? java.util.logging.Level.SEVERE : java.util.logging.Level.WARNING,
                        buildPlainText(code, identity, context, source));
    }

    /**
     * Convenience: extract the numeric code from a MegaAPIException message
     * (which is always shaped "MEGA API ERROR: -N" or "MEGA API ERROR: -N extra
     * info..."). Returns 0 if it can't parse. Useful when callers caught a
     * generic Exception and only kept its message.
     */
    public static int parseCodeFromMessage(String message) {
        if (message == null) {
            return 0;
        }
        String m = MiscTools.findFirstRegex("MEGA API ERROR:\\s*(-?\\d+)", message, 1);
        if (m == null) {
            return 0;
        }
        try {
            return Integer.parseInt(m);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
