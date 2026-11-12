/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.MainPanel.VERSION;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;

/**
 *
 * @author tonikelope
 */
public class MiscTools {

    public static final int EXP_BACKOFF_BASE = 2;
    public static final int EXP_BACKOFF_SECS_RETRY = 1;
    public static final int EXP_BACKOFF_MAX_WAIT_TIME = 8;
    public static final Object PASS_LOCK = new Object();
    public static final int HTTP_TIMEOUT = 30;
    public static final String UPLOAD_LOGS_DIR = System.getProperty("user.home") + File.separator + "MEGABASTERD_UPLOAD_LOGS";

    private static final Logger LOG = Logger.getLogger(MiscTools.class.getName());

    public static String computeFileSHA1(File file) throws IOException {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file));
            int n = 0;
            byte[] buffer = new byte[8192];
            while (n != -1) {
                n = fis.read(buffer);
                if (n > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            return new HexBinaryAdapter().marshal(digest.digest()).toLowerCase().trim();
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

    public static void createUploadLogDir() {

        if (!Files.exists(Paths.get(UPLOAD_LOGS_DIR))) {
            try {
                Files.createDirectory(Paths.get(UPLOAD_LOGS_DIR));

                File dir = new File(System.getProperty("user.home"));

                for (File file : dir.listFiles()) {
                    if (!file.isDirectory() && file.getName().startsWith("megabasterd_upload_")) {
                        Files.move(file.toPath(), Paths.get(UPLOAD_LOGS_DIR + File.separator + file.getName()));
                    }
                }

            } catch (IOException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
            }

        }
    }

    public static void purgeFolderCache() {
        File directory = new File(System.getProperty("java.io.tmpdir"));

        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }

        for (File f : entries) {
            if (f.isFile() && f.getName().startsWith("megabasterd_folder_cache_")) {
                f.delete();
                Logger.getLogger(MiscTools.class.getName()).log(Level.INFO, "REMOVING FOLDER CACHE FILE {0}", f.getAbsolutePath());
            }
        }
    }

    public static void purgeOrphanThumbnails() {

        // Thumbnails are normally consumed by Upload.run within seconds and
        // then deleted, but if MegaBasterd crashed or was force-killed
        // mid-upload they orphan in java.io.tmpdir. Sweep on startup so the
        // tmpdir doesn't fill over time.
        File directory = new File(System.getProperty("java.io.tmpdir"));

        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }

        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L; // 1h grace

        for (File f : entries) {
            if (f.isFile() && f.getName().startsWith("megabasterd_thumbnail_") && f.lastModified() < cutoff) {
                if (f.delete()) {
                    Logger.getLogger(MiscTools.class.getName()).log(Level.FINE, "REMOVING ORPHAN THUMBNAIL {0}", f.getAbsolutePath());
                }
            }
        }
    }

    public static String getFechaHoraActual() {

        String format = "dd-MM-yyyy HH:mm:ss";

        return getFechaHoraActual(format);
    }

    public static boolean isVideoFile(String filename) {

        try {

            String part_file = MiscTools.findFirstRegex("\\.part[0-9]+-[0-9]+$", filename, 0);

            return part_file == null && Files.probeContentType(Paths.get(filename)).startsWith("video/");
        } catch (IOException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

        return false;
    }

    public static boolean isImageFile(String filename) {

        try {

            String part_file = MiscTools.findFirstRegex("\\.part[0-9]+-[0-9]+$", filename, 0);

            return part_file == null && Files.probeContentType(Paths.get(filename)).startsWith("image/");
        } catch (IOException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

        return false;
    }

    public static String getFechaHoraActual(String format) {

        Date currentDate = new Date(System.currentTimeMillis());

        DateFormat df = new SimpleDateFormat(format, java.util.Locale.ROOT);

        return df.format(currentDate);
    }

    public static void deleteDirectoryRecursion(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteDirectoryRecursion(entry);
                }
            }
        }
        Files.delete(path);
    }

    public static int[] bin2i32a(byte[] bin) {
        int l = (int) (4 * Math.ceil((double) bin.length / 4));

        IntBuffer intBuf = ByteBuffer.wrap(bin, 0, l).order(ByteOrder.BIG_ENDIAN).asIntBuffer();

        int[] array = new int[intBuf.remaining()];

        intBuf.get(array);

        return array;
    }

    public static byte[] i32a2bin(int[] values) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        DataOutputStream dos = new DataOutputStream(baos);

        for (int i = 0; i < values.length; ++i) {
            try {
                dos.writeInt(values[i]);
            } catch (IOException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        return baos.toByteArray();
    }

    public static BigInteger mpi2big(byte[] s) {

        byte[] ns = Arrays.copyOfRange(s, 2, s.length);

        BigInteger bigi = new BigInteger(1, ns);

        return bigi;
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String genID(int length) {

        String pos = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        StringBuilder res = new StringBuilder(length);

        for (int i = 0; i < length; i++) {

            res.append(pos.charAt(SECURE_RANDOM.nextInt(pos.length())));
        }

        return res.toString();
    }

    public static byte[] long2bytearray(long val) {

        byte[] b = new byte[8];

        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) val;
            val >>>= 8;
        }

        return b;
    }

    public static long bytearray2long(byte[] val) {

        long l = 0;

        for (int i = 0; i < 8; i++) {
            l = (l << 8) | (val[i] & 0xFFL);
        }

        return l;
    }

    public static void drainAndCloseErrorStream(HttpURLConnection con) {

        if (con == null) {
            return;
        }

        InputStream es = con.getErrorStream();

        if (es == null) {
            return;
        }

        try (InputStream toDrain = es) {
            byte[] buf = new byte[4096];
            while (toDrain.read(buf) != -1) {
                // discard
            }
        } catch (IOException ignore) {
        }
    }

    public static int parseIntOr(String value, int fallback) {

        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.WARNING, "Bad integer setting {0}, using fallback {1}", new Object[]{value, fallback});
            return fallback;
        }
    }

    public static long parseLongOr(String value, long fallback) {

        if (value == null) {
            return fallback;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.WARNING, "Bad long setting {0}, using fallback {1}", new Object[]{value, fallback});
            return fallback;
        }
    }

    public static String findFirstRegex(String regex, String data, int group) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);

        Matcher matcher = pattern.matcher(data);

        return matcher.find() ? matcher.group(group) : null;
    }

    public static ArrayList<String> findAllRegex(String regex, String data, int group) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);

        Matcher matcher = pattern.matcher(data);

        ArrayList<String> matches = new ArrayList<>();

        while (matcher.find()) {
            matches.add(matcher.group(group));
        }

        return matches;
    }

    public static String HashString(String algo, String data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);

            byte[] thedigest = md.digest(data.getBytes("UTF-8"));

            return bin2hex(thedigest);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

    public static String HashString(String algo, byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algo);

        byte[] thedigest = md.digest(data);

        return bin2hex(thedigest);
    }

    public static byte[] HashBin(String algo, String data) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest md = MessageDigest.getInstance(algo);

        return md.digest(data.getBytes("UTF-8"));
    }

    public static byte[] HashBin(String algo, byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algo);

        return md.digest(data);
    }

    public static byte[] BASE642Bin(String data) {
        return Base64.getDecoder().decode(data);
    }

    public static String Bin2BASE64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] UrlBASE642Bin(String data) {
        return Base64.getUrlDecoder().decode(data);
    }

    public static String Bin2UrlBASE64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static void pausar(long pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public static long getWaitTimeExpBackOff(int retryCount) {

        long waitTime = ((long) Math.pow(EXP_BACKOFF_BASE, retryCount) * EXP_BACKOFF_SECS_RETRY);

        return Math.min(waitTime, EXP_BACKOFF_MAX_WAIT_TIME);
    }

    public static String bin2hex(byte[] b) {

        BigInteger bi = new BigInteger(1, b);

        return String.format("%0" + (b.length << 1) + "x", bi);
    }

    public static byte[] hex2bin(String s) {
        return DatatypeConverter.parseHexBinary(s);
    }

    private static final String[] FORMAT_BYTES_UNITS = {"B", "KB", "MB", "GB", "TB"};

    // DecimalFormat is not thread-safe but is allocated on hot paths (every
    // SpeedMeter tick × every row, every memory-monitor tick). Cache one per thread. Locale.ROOT for consistent
    // "1.5 MB" across locales (was "1,5 MB" on Spanish/German systems).
    private static final ThreadLocal<DecimalFormat> FORMAT_BYTES_DF = ThreadLocal.withInitial(
            () -> new DecimalFormat("#.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)));

    public static String formatBytes(Long bytes) {

        if (bytes == null) {
            return "0 B";
        }

        long b = Math.max(bytes, 0L);

        int pow = Math.min((int) ((b > 0L ? Math.log(b) : 0) / Math.log(1024)), FORMAT_BYTES_UNITS.length - 1);

        double bytes_double = (double) b / (1L << (10 * pow));

        return FORMAT_BYTES_DF.get().format(bytes_double) + ' ' + FORMAT_BYTES_UNITS[pow];
    }

    public static boolean isDirEmpty(Path path) {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> directory = Files.newDirectoryStream(path)) {
                return !directory.iterator().hasNext();
            } catch (IOException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        return false;
    }

    public static String truncateText(String text, int max_length) {

        String separator = " ... ";

        max_length -= separator.length();

        if (max_length % 2 != 0) {

            max_length--;
        }

        return (text.length() > max_length) ? text.replaceAll("^(.{1," + (max_length / 2) + "}).*?(.{1," + (max_length / 2) + "})$", "$1" + separator + "$2") : text;
    }

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private static final java.util.regex.Pattern WIN_RESERVED_NAMES = java.util.regex.Pattern.compile(
            "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\.|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static boolean isWindowsOS() {
        return IS_WINDOWS;
    }

    public static String cleanFilename(String filename) {

        // Collapse runs of 2+ dots to a single dot rather than replacing with
        // "__": the old behaviour destroyed the extension on names like
        // "1. Hello World..mp4" (-> "1. Hello World__mp4", no extension).
        // After slash stripping there is no path-traversal risk from "..",
        // and "." inside a filename is just a literal character.
        String cleaned = (IS_WINDOWS ? filename.replaceAll("[<>:\"/\\\\\\|\\?\\*\t]+", "") : filename)
                .replaceAll("\\" + File.separator, "")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("[\\x00-\\x1F]", "")
                .trim();

        if (IS_WINDOWS) {
            // Windows reserved names: CON, PRN, AUX, NUL, COM1..COM9, LPT1..LPT9.
            // Forbidden as bare name or as basename even with extension.
            if (WIN_RESERVED_NAMES.matcher(cleaned).find()) {
                cleaned = "_" + cleaned;
            }
            // Trailing dots and spaces are illegal on Windows.
            while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
        }

        // Bound filename to 250 bytes-ish to leave room within the typical
        // 255 char filename limit and the 260 char Windows MAX_PATH.
        if (cleaned.length() > 250) {
            int dot = cleaned.lastIndexOf('.');
            String ext = (dot >= 0 && dot > cleaned.length() - 16) ? cleaned.substring(dot) : "";
            String base = (dot >= 0 && dot > cleaned.length() - 16) ? cleaned.substring(0, dot) : cleaned;
            int keep = 250 - ext.length();
            if (keep < 1) {
                keep = 1;
            }
            cleaned = base.substring(0, Math.min(base.length(), keep)) + ext;
        }

        return cleaned;
    }

    public static String cleanFilePath(String path) {

        // See cleanFilename for the dot-collapse rationale. Slashes are kept
        // here (this is a relative path), and collapsing "../foo" to "./foo"
        // still resolves harmlessly inside the download base directory.
        return !path.equals(".") ? ((IS_WINDOWS ? path.replaceAll("[<>:\"\\|\\?\\*\t]+", "") : path).replaceAll(" +\\" + File.separator, "\\" + File.separator).replaceAll("\\.{2,}", ".").replaceAll("[\\x00-\\x1F]", "").trim()) : path;
    }

    public static byte[] genRandomByteArray(int length) {

        byte[] the_array = new byte[length];

        SECURE_RANDOM.nextBytes(the_array);

        return the_array;
    }

    public static String extractMegaLinksFromString(String data) {

        String res = "";

        if (data != null) {

            if (data.startsWith("moz-extension") || data.startsWith("chrome-extension")) {
                data = extensionURL2NormalLink(data);
            }

            ArrayList<String> links = new ArrayList<>();
            String url_decoded;
            try {
                url_decoded = URLDecoder.decode(data, "UTF-8");
            } catch (Exception ex) {
                url_decoded = data;
            }
            ArrayList<String> base64_chunks = findAllRegex("[A-Za-z0-9+/_-]+=*", url_decoded, 0);
            if (!base64_chunks.isEmpty()) {

                for (String chunk : base64_chunks) {

                    try {

                        String clean_data = MiscTools.newMegaLinks2Legacy(new String(Base64.getDecoder().decode(chunk), java.nio.charset.StandardCharsets.UTF_8));

                        String decoded = MiscTools.findFirstRegex("(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+", clean_data, 0);

                        if (decoded != null) {
                            links.add(decoded);
                        }

                    } catch (Exception e) {
                    };
                }
            }
            try {
                url_decoded = URLDecoder.decode(data, "UTF-8");
            } catch (Exception ex) {
                url_decoded = data;
            }
            String clean_data = MiscTools.newMegaLinks2Legacy(url_decoded);
            links.addAll(findAllRegex("(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+", clean_data, 0));
            links.addAll(findAllRegex("mega://e(n|l)c[^\r\n]+", clean_data, 0));
            res = links.stream().map((s) -> s + "\n").reduce(res, String::concat);
        }

        return res.trim();
    }

    public static String extractFirstMegaLinkFromString(String data) {

        String res = "";

        if (data != null) {

            try {
                String clean_data = MiscTools.newMegaLinks2Legacy(URLDecoder.decode(data, "UTF-8"));

                ArrayList<String> links = findAllRegex("(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+", clean_data, 0);

                links.addAll(findAllRegex("mega://e(n|l)c[^\r\n]+", clean_data, 0));

                if (links.size() > 0) {

                    res = links.get(0);
                }
            } catch (UnsupportedEncodingException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
            }
        }

        return res;
    }

    public static boolean checkMegaDownloadUrl(String string_url) {

        if (string_url == null || "".equals(string_url)) {
            return false;
        }

        HttpURLConnection con = null;

        int http_status = 0, http_error = 0;

        // Sentinel: non-null (holds "vpn") while the most recent attempt
        // went through ExpressVPN-protected routing. See ChunkDownloader
        // for the same pattern/rationale.
        String current_smart_proxy = null;

        do {

            VpnRotationManager vpn_manager = MainPanel.getProxy_manager();

            if (MainPanel.isUse_smart_proxy() && vpn_manager != null && vpn_manager.isForceVpn()) {

                current_smart_proxy = vpn_manager.ensureProtectiveRouting();

                if (current_smart_proxy == null) {
                    LOG.log(Level.WARNING, "checkMegaDownloadUrl: VPN force-mode connect failed -- checking direct");
                }
            }

            try {

                URL url = new URL(string_url + "/0-0");

                if ((current_smart_proxy != null || http_error == 509) && MainPanel.isUse_smart_proxy() && vpn_manager != null && !MainPanel.isUse_proxy()) {

                    if (current_smart_proxy != null && http_error != 0) {
                        vpn_manager.reportQuotaError(current_smart_proxy, "HTTP " + String.valueOf(http_error));
                    }

                    current_smart_proxy = vpn_manager.ensureProtectiveRouting();

                    // ExpressVPN routes ALL of the machine's traffic through
                    // the tunnel at the OS level once connected -- no
                    // java.net.Proxy object needed (or meaningful).
                    con = (HttpURLConnection) url.openConnection();

                } else {

                    if (MainPanel.isUse_proxy()) {

                        con = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                        if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                            con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes("UTF-8")));
                        }
                    } else {

                        con = (HttpURLConnection) url.openConnection();
                    }
                }

                if (current_smart_proxy != null && vpn_manager != null) {
                    con.setConnectTimeout(vpn_manager.getConnectTimeoutMs());
                    con.setReadTimeout(vpn_manager.getConnectTimeoutMs() * 2);
                }

                con.setUseCaches(false);

                con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                http_status = con.getResponseCode();

                if (http_status != 200) {
                    http_error = http_status;
                    drainAndCloseErrorStream(con);
                } else {
                    http_error = 0;
                }

            } catch (Exception ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
            } finally {

                if (con != null) {
                    con.disconnect();
                }

            }

        } while (http_error == 509);

        return http_status != 403;
    }

    /**
     * Endpoints that return the caller's public IPv4 as plain text in the
     * response body. Rotated through in order until one returns a valid IP. All
     * HTTPS so a hostile network on the path can't spoof the response and
     * mislead the 509-recovery logic into believing the IP changed (or didn't).
     * The previous single source ("http://whatismyip.akamai.com/") was plain
     * HTTP and a single point of failure -- if akamai's box was unreachable or
     * an MITM rewrote the response, the IP-aware retry would mis-trigger.
     * (#751)
     */
    private static final String[] PUBLIC_IP_SOURCES = new String[]{
        "https://api.ipify.org/",
        "https://ifconfig.me/ip",
        "https://icanhazip.com/",
        "https://ipv4.icanhazip.com/",
        "https://checkip.amazonaws.com/"
    };

    // Tight timeouts: under worst case (all 5 sources hang to read-timeout)
    // total wall time = 5 sources * (connect + read) = 5 * 4s = 20s. The
    // MainPanel.getCachedPublicIp lock is held during this call so we want
    // it bounded enough that contended workers don't pile up. Five sources
    // is enough redundancy that a 2s read window covers any responsive endpoint.
    private static final int PUBLIC_IP_CONNECT_TIMEOUT_MS = 2_000;
    private static final int PUBLIC_IP_READ_TIMEOUT_MS = 2_000;

    /**
     * Bounded-validate the response body of one of the IPv4 services. They
     * sometimes append a trailing newline or extra whitespace; trim and then
     * verify dotted-quad shape. Reject anything that doesn't look like an IP so
     * we don't poison the cache with HTML error pages.
     */
    private static String parsePublicIpResponse(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        // Bound the input to avoid pathological regex on a giant body.
        if (trimmed.length() > 64) {
            return null;
        }
        if (trimmed.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            return trimmed;
        }
        return null;
    }

    public static String getMyPublicIP() {

        for (String source : PUBLIC_IP_SOURCES) {
            HttpURLConnection con = null;

            try {

                URL url_api = new URL(source);

                if (MainPanel.isUse_proxy()) {

                    con = (HttpURLConnection) url_api.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                    if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                        con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes("UTF-8")));
                    }
                } else {

                    con = (HttpURLConnection) url_api.openConnection();
                }

                con.setUseCaches(false);
                con.setConnectTimeout(PUBLIC_IP_CONNECT_TIMEOUT_MS);
                con.setReadTimeout(PUBLIC_IP_READ_TIMEOUT_MS);
                con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                int status = con.getResponseCode();
                if (status != 200) {
                    Logger.getLogger(MiscTools.class.getName()).log(Level.FINE,
                            "Public IP source {0} returned HTTP {1}", new Object[]{source, status});
                    drainAndCloseErrorStream(con);
                    continue;
                }

                String body;
                try (InputStream is = con.getInputStream(); ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[MainPanel.DEFAULT_BYTE_BUFFER_SIZE];

                    int reads;

                    while ((reads = is.read(buffer)) != -1) {

                        byte_res.write(buffer, 0, reads);
                    }

                    body = new String(byte_res.toByteArray(), "UTF-8");
                }

                String parsed = parsePublicIpResponse(body);
                if (parsed != null) {
                    return parsed;
                }

                Logger.getLogger(MiscTools.class.getName()).log(Level.FINE,
                        "Public IP source {0} returned unparseable body (len={1})",
                        new Object[]{source, body.length()});

            } catch (MalformedURLException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
            } catch (IOException ex) {
                Logger.getLogger(MiscTools.class.getName()).log(Level.FINE,
                        "Public IP source {0} failed: {1}", new Object[]{source, ex.getMessage()});
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }

        Logger.getLogger(MiscTools.class.getName()).log(Level.WARNING,
                "All {0} public IP sources failed", PUBLIC_IP_SOURCES.length);
        return null;
    }

    public static String checkNewVersion(String url) {

        String new_version_major = null, new_version_minor = null, current_version_major = null, current_version_minor = null;

        URL mb_url;

        HttpURLConnection con = null;

        try {

            mb_url = new URL(url);

            if (MainPanel.isUse_proxy()) {

                con = (HttpURLConnection) mb_url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                    con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes("UTF-8")));
                }
            } else {

                con = (HttpURLConnection) mb_url.openConnection();
            }

            con.setUseCaches(false);

            try (InputStream is = con.getInputStream(); ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[MainPanel.DEFAULT_BYTE_BUFFER_SIZE];

                int reads;

                while ((reads = is.read(buffer)) != -1) {

                    byte_res.write(buffer, 0, reads);
                }

                String latest_version_res = new String(byte_res.toByteArray(), "UTF-8");

                String latest_version = findFirstRegex("releases\\/tag\\/v?([0-9]+\\.[0-9]+)", latest_version_res, 1);

                if (latest_version == null) {
                    // GitHub page format changed; bail without crashing.
                    Logger.getLogger(MiscTools.class.getName()).log(Level.FINE, "checkNewVersion: could not extract latest tag from response");
                    return null;
                }

                new_version_major = findFirstRegex("([0-9]+)\\.[0-9]+", latest_version, 1);

                new_version_minor = findFirstRegex("[0-9]+\\.([0-9]+)", latest_version, 1);

                current_version_major = findFirstRegex("([0-9]+)\\.[0-9]+$", VERSION, 1);

                current_version_minor = findFirstRegex("[0-9]+\\.([0-9]+)$", VERSION, 1);

                if (new_version_major != null && new_version_minor != null && current_version_major != null && current_version_minor != null
                        && (Integer.parseInt(current_version_major) < Integer.parseInt(new_version_major)
                        || (Integer.parseInt(current_version_major) == Integer.parseInt(new_version_major) && Integer.parseInt(current_version_minor) < Integer.parseInt(new_version_minor)))) {

                    return new_version_major + "." + new_version_minor;

                }
            }

        } catch (MalformedURLException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
        } catch (IOException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }

        return null;
    }

    public static byte[] recReverseArray(byte[] arr, int start, int end) {

        byte temp;

        if (start < end) {
            temp = arr[start];

            arr[start] = arr[end];

            arr[end] = temp;

            return recReverseArray(arr, start + 1, end - 1);

        } else {
            return arr;
        }
    }

    public static String getCurrentJarParentPath() {
        try {
            CodeSource codeSource = MainPanel.class.getProtectionDomain().getCodeSource();

            File jarFile = new File(codeSource.getLocation().toURI().getPath());

            return jarFile.getParentFile().getAbsolutePath();

        } catch (URISyntaxException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

    public static void restartApplication() {

        java.util.List<String> argv = new java.util.ArrayList<>();

        argv.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

        argv.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

        argv.add("-cp");
        argv.add(ManagementFactory.getRuntimeMXBean().getClassPath());

        // shortcut: sun.java.command is space-joined, so arguments containing spaces are not preserved; pass argv in from main() if that ever matters
        String[] command = System.getProperty("sun.java.command", "").trim().split("\\s+");
        if (command[0].endsWith(".jar")) {
            argv.add("-jar");
        }
        argv.addAll(Arrays.asList(command));

        try {
            new ProcessBuilder(argv).inheritIO().start();
        } catch (IOException ex) {
            Logger.getLogger(MiscTools.class.getName()).log(Level.SEVERE, ex.getMessage());
        }

        System.exit(2);
    }

    /*
        Thanks -> https://stackoverflow.com/a/26884326
     */
    public static int naturalCompare(String a, String b, boolean ignoreCase) {

        if (a == null) {
            a = "";
        }

        if (b == null) {
            b = "";
        }

        if (ignoreCase) {
            a = a.toLowerCase(java.util.Locale.ROOT);
            b = b.toLowerCase(java.util.Locale.ROOT);
        }
        int aLength = a.length();
        int bLength = b.length();
        int minSize = Math.min(aLength, bLength);
        char aChar, bChar;
        boolean aNumber, bNumber;
        boolean asNumeric = false;
        int lastNumericCompare = 0;
        for (int i = 0; i < minSize; i++) {
            aChar = a.charAt(i);
            bChar = b.charAt(i);
            aNumber = aChar >= '0' && aChar <= '9';
            bNumber = bChar >= '0' && bChar <= '9';
            if (asNumeric) {
                if (aNumber && bNumber) {
                    if (lastNumericCompare == 0) {
                        lastNumericCompare = aChar - bChar;
                    }
                } else if (aNumber) {
                    return 1;
                } else if (bNumber) {
                    return -1;
                } else if (lastNumericCompare == 0) {
                    if (aChar != bChar) {
                        return aChar - bChar;
                    }
                    asNumeric = false;
                } else {
                    return lastNumericCompare;
                }
            } else if (aNumber && bNumber) {
                asNumeric = true;
                if (lastNumericCompare == 0) {
                    lastNumericCompare = aChar - bChar;
                }
            } else if (aChar != bChar) {
                return aChar - bChar;
            }
        }
        if (asNumeric) {
            if (aLength > bLength && a.charAt(bLength) >= '0' && a.charAt(bLength) <= '9') // as number
            {
                return 1;  // a has bigger size, thus b is smaller
            } else if (bLength > aLength && b.charAt(aLength) >= '0' && b.charAt(aLength) <= '9') // as number
            {
                return -1;  // b has bigger size, thus a is smaller
            } else if (lastNumericCompare == 0) {
                return aLength - bLength;
            } else {
                return lastNumericCompare;
            }
        } else {
            return aLength - bLength;
        }
    }

    /**
     * Prompts for the master password via the app's UserPrompter and
     * validates it against the stored PBKDF2/SHA-1 hash. Retries a few
     * times on a wrong password; returns null if the user gives up or no
     * input is available.
     */
    static byte[] promptMasterPasswordHeadless(MainPanel main_panel) {

        final int MAX_ATTEMPTS = 3;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            char[] entered = main_panel.getUserPrompter().promptPassword("Master password:");

            if (entered == null || entered.length == 0) {
                return null;
            }

            try {
                byte[] pass = CryptTools.PBKDF2HMACSHA256(new String(entered), BASE642Bin(main_panel.getMaster_pass_salt()), CryptTools.MASTER_PASSWORD_PBKDF2_ITERATIONS, CryptTools.MASTER_PASSWORD_PBKDF2_OUTPUT_BIT_LENGTH);

                String pass_hash = Bin2BASE64(HashBin("SHA-1", pass));

                String current_hash = main_panel.getMaster_pass_hash();

                // Constant-time compare.
                if (java.security.MessageDigest.isEqual(
                        pass_hash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        current_hash == null ? new byte[0] : current_hash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {

                    return pass;
                }

                LOG.log(Level.WARNING, "Wrong master password ({0}/{1})", new Object[]{attempt, MAX_ATTEMPTS});

            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Master password verification failed: {0}", ex.getMessage());
                return null;
            } finally {
                Arrays.fill(entered, '\0');
            }
        }

        return null;
    }

    public static MegaAPI checkMegaAccountLoginAndShowMasterPassDialog(MainPanel main_panel, String email) throws Exception {

        HashMap<String, Object> account_info = (HashMap) main_panel.getMega_accounts().get(email);

        MegaAPI ma = main_panel.getMega_active_accounts().get(email);

        if (ma == null) {

            ma = new MegaAPI();

            String password_aes, user_hash;
            synchronized (PASS_LOCK) {

                if (main_panel.getMaster_pass_hash() != null) {

                    if (main_panel.getMaster_pass() == null) {

                        byte[] entered_pass = promptMasterPasswordHeadless(main_panel);

                        if (entered_pass != null) {

                            main_panel.setMaster_pass(entered_pass);

                            password_aes = Bin2BASE64(CryptTools.aes_cbc_decrypt_at_rest(BASE642Bin((String) account_info.get("password_aes")), main_panel.getMaster_pass()));

                            user_hash = Bin2BASE64(CryptTools.aes_cbc_decrypt_at_rest(BASE642Bin((String) account_info.get("user_hash")), main_panel.getMaster_pass()));

                        } else {

                            return null;
                        }

                    } else {

                        password_aes = Bin2BASE64(CryptTools.aes_cbc_decrypt_at_rest(BASE642Bin((String) account_info.get("password_aes")), main_panel.getMaster_pass()));

                        user_hash = Bin2BASE64(CryptTools.aes_cbc_decrypt_at_rest(BASE642Bin((String) account_info.get("user_hash")), main_panel.getMaster_pass()));

                    }

                } else {

                    password_aes = (String) account_info.get("password_aes");

                    user_hash = (String) account_info.get("user_hash");
                }

                try {

                    HashMap<String, Object> old_session_data = DBTools.selectMegaSession(email);

                    boolean unserialization_error = false;

                    if (old_session_data != null) {

                        Logger.getLogger(MiscTools.class.getName()).log(Level.INFO, "Reutilizando sesión de MEGA guardada para {0}", email);

                        MegaAPI old_ma = new MegaAPI();

                        if ((boolean) old_session_data.get("crypt")) {

                            ByteArrayInputStream bs = new ByteArrayInputStream(CryptTools.aes_cbc_decrypt_at_rest((byte[]) old_session_data.get("ma"), main_panel.getMaster_pass()));

                            try (ObjectInputStream is = new ObjectInputStream(bs)) {

                                old_ma = (MegaAPI) is.readObject();

                            } catch (Exception ex) {
                                unserialization_error = true;
                            }

                        } else {

                            ByteArrayInputStream bs = new ByteArrayInputStream((byte[]) old_session_data.get("ma"));

                            try (ObjectInputStream is = new ObjectInputStream(bs)) {
                                old_ma = (MegaAPI) is.readObject();
                            } catch (Exception ex) {
                                unserialization_error = true;
                            }

                        }

                        if (old_ma.getQuota() == null) {

                            unserialization_error = true;

                        } else {

                            ma = old_ma;
                        }
                    }

                    if (old_session_data == null || unserialization_error) {

                        String pincode = null;

                        if (ma.check2FA(email)) {

                            pincode = main_panel.getUserPrompter().prompt2FACode(email);

                            if (pincode == null || pincode.isEmpty()) {
                                throw new MegaAPIException(-26);
                            }
                        }

                        ma.fastLogin(email, bin2i32a(BASE642Bin(password_aes)), user_hash, pincode);

                        ByteArrayOutputStream bs = new ByteArrayOutputStream();

                        try (ObjectOutputStream os = new ObjectOutputStream(bs)) {
                            os.writeObject(ma);
                        }

                        if (main_panel.getMaster_pass() != null) {

                            DBTools.insertMegaSession(email, CryptTools.aes_cbc_encrypt_at_rest(bs.toByteArray(), main_panel.getMaster_pass()), true);

                        } else {

                            DBTools.insertMegaSession(email, bs.toByteArray(), false);
                        }
                    }

                    main_panel.getMega_active_accounts().put(email, ma);

                } catch (MegaAPIException exception) {

                    if (exception.getCode() == -6) {
                        String msg = LabelTranslatorSingleton.getInstance().translate("You've tried to login too many times. Wait an hour.");
                        main_panel.getUserPrompter().showError(I18n.tr("ui.error_title"), msg);
                    }

                    throw exception;
                }

            }
        }

        return ma;

    }

    public static String newMegaLinks2Legacy(String data) {

        data = MiscTools.addBackSlashToLinks(MiscTools.addHTTPSToMegaLinks(data));

        data = data.replaceAll("(?:https://)?mega(?:\\.co)?\\.nz/folder/([^#]+)#([^\r\n/]+)/file/([^\r\n/]+)", "https://mega.nz/#F*$3!$1!$2");

        data = data.replaceAll("(?:https://)?mega(?:\\.co)?\\.nz/folder/([^#]+)#([^\r\n/]+)/folder/([^\r\n/]+)", "https://mega.nz/#F!$1@$3!$2");

        return data.replaceAll("(?:https://)?mega(?:\\.co)?\\.nz/folder/([^#]+)#([^\r\n]+)", "https://mega.nz/#F!$1!$2").replaceAll("(?:https://)?mega(?:\\.co)?\\.nz/file/([^#]+)#([^\r\n]+)", "https://mega.nz/#!$1!$2");
    }

    public static String addHTTPSToMegaLinks(String data) {

        return data.replaceAll("(?<!https?://)mega(?:\\.co)?\\.nz", "https://mega.nz");
    }

    public static String addBackSlashToLinks(String data) {

        return data.replaceAll("https?://", "\n$0");
    }

    /* This method changes the MEGA extension URL to a ordinary MEGA URL,
    so copying the extension URL from Firefox or Chrome also works as a normal URL */
    public static String extensionURL2NormalLink(String data) {

        String toReplace = data.substring(0, data.indexOf('#') + 1);

        return data.replace(toReplace, "https://mega.nz");
    }

    private MiscTools() {
    }

}
