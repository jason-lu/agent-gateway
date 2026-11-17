package dev.kualu.llm.memory;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic detector for hard identifiers: phone numbers, emails, ID cards,
 * bank cards, precise addresses, and credentials. Hard identifiers are never
 * stored in semantic memory and never sent to the model; every write path and
 * the pre-injection rescan go through this class.
 *
 * <p>Fails closed: an unclassifiable high-risk pattern (e.g. a long digit run
 * that is neither a validated card nor a timestamp) still counts as a hit.
 * Only the category is reported — the matched text is never returned or logged.
 */
public final class HardIdentifierDetector {

    /** Detection category; for logs and refusal text only, never the input itself. */
    public record Hit(String category) {
    }

    public static final String PHONE = "phone";
    public static final String EMAIL = "email";
    public static final String ID_CARD = "id-card";
    public static final String BANK_CARD = "bank-card";
    public static final String ADDRESS = "address";
    public static final String CREDENTIAL = "credential";
    public static final String UNKNOWN = "unknown";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
            "(?:账号|帐号|用户名|登录名|密码|口令|密钥|token|apikey|api[-_ ]?key|password|passwd|secret)"
                    + "\\s*(?:[:：=]|是|为|is)\\s*\\S{2,}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{6}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");

    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** Separated mobile: 138-12345678 / 138 1234 5678 / 138-1234-5678. */
    private static final Pattern MOBILE_SEPARATED_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");

    /** Landline: area code + subscriber number, dash required to avoid timestamps. */
    private static final Pattern LANDLINE_PATTERN = Pattern.compile(
            "(?<!\\d)0\\d{2,3}-\\d{7,8}(?!\\d)");

    /** Digit run with optional single separators; 12-25 digits once stripped. */
    private static final Pattern DIGIT_RUN_PATTERN = Pattern.compile(
            "\\d(?:[ -]?\\d){11,24}");

    /** Building/unit numbers: 5室 / 3单元 / 2栋 / 12号楼. Bare 号 is left out (dates). */
    private static final Pattern BUILDING_PATTERN = Pattern.compile(
            "\\d+\\s*(?:号楼|室|单元|栋|幢)");

    /** Street with a house number: 文一西路100号 / 中山大道 88 号. */
    private static final Pattern STREET_PATTERN = Pattern.compile(
            "[一-鿿]{1,10}(?:路|街|巷|弄|大道|大街)\\s*\\d+\\s*号?");

    /** Compound + building number: 阳光小区3号楼. */
    private static final Pattern COMPOUND_PATTERN = Pattern.compile(
            "[一-鿿]{1,6}(?:小区|花园|公寓)\\s*\\d+");

    /** Town / subdistrict without a number: 良渚街道 / 长安镇. */
    private static final Pattern TOWN_PATTERN = Pattern.compile(
            "[一-鿿]{1,6}(?:街道|镇)");

    /** Bare street name: 文一西路 (no house number needed). */
    private static final Pattern BARE_STREET_PATTERN = Pattern.compile(
            "[一-鿿]{2,8}(?:路|大道)");

    /** District / county name candidates: 西湖区 / 余杭区 / 海淀区. */
    private static final Pattern DISTRICT_PATTERN = Pattern.compile(
            "[一-鿿]{1,4}(?:区|县)");

    /**
     * Name-part suffixes that mark zone-like compounds rather than addresses
     * (开发区、园区、地区、小区…). A district candidate whose name part ends
     * with one of these is skipped; everything else hits. Bare 区-homographs
     * that occur inside real district names (山/湖/新…) are deliberately NOT
     * listed: a false block is acceptable, a leak of a real district is not.
     */
    private static final Set<String> ZONE_NAME_SUFFIXES = Set.of(
            "开发", "高新", "园", "地", "辖", "特", "禁", "时", "分", "军",
            "误", "盲", "小", "厂", "校", "老", "自治", "试验", "保税",
            "产业", "科技", "工业", "农业", "旅游", "风景", "生态", "居住",
            "教育", "文化", "体育", "经济", "渔", "垦");

    /**
     * Name-part suffixes that make 街道/镇/路/大道 a generic word rather than
     * a place (城镇、乡镇、古镇、思路、公路…). Same fail-closed trade-off as
     * {@link #ZONE_NAME_SUFFIXES}.
     */
    private static final Set<String> GENERIC_PLACE_SUFFIXES = Set.of(
            "城", "乡", "古", "园", "思", "线", "马", "公", "铁", "道",
            "出", "上", "回", "引", "电", "链", "网", "通", "短", "断",
            "岔", "扫", "销", "跑", "赶", "活", "末", "半", "弯", "顺",
            "迷", "门", "财", "发", "退", "同", "熟", "无", "投", "行", "一", "全", "水", "条", "小");

    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECKS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /** Date prefix (YYYYMMDD…) — a timestamp, not an account number. */
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])");

    /** Scans text for a hard identifier; empty when the text is clean. */
    public Optional<Hit> scan(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(text);

        if (EMAIL_PATTERN.matcher(normalized).find()) {
            return Optional.of(new Hit(EMAIL));
        }
        if (CREDENTIAL_PATTERN.matcher(normalized).find()) {
            return Optional.of(new Hit(CREDENTIAL));
        }
        if (findIdCard(normalized)) {
            return Optional.of(new Hit(ID_CARD));
        }
        if (MOBILE_PATTERN.matcher(normalized).find()
                || MOBILE_SEPARATED_PATTERN.matcher(normalized).find()
                || LANDLINE_PATTERN.matcher(normalized).find()) {
            return Optional.of(new Hit(PHONE));
        }
        if (findAddress(normalized)) {
            return Optional.of(new Hit(ADDRESS));
        }
        if (findBankCard(normalized)) {
            return Optional.of(new Hit(BANK_CARD));
        }
        if (findUnexplainedDigitRun(normalized)) {
            return Optional.of(new Hit(UNKNOWN));
        }
        return Optional.empty();
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + (c - '０')));
            } else if (c == '＠') {
                out.append('@');
            } else if (c == '．') {
                out.append('.');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean findIdCard(String text) {
        Matcher matcher = ID_CARD_PATTERN.matcher(text);
        while (matcher.find()) {
            if (hasValidIdCardChecksum(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /** GB 11643 checksum: weighted sum mod 11 maps to a check character. */
    private static boolean hasValidIdCardChecksum(String id) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * ID_CARD_WEIGHTS[i];
        }
        return ID_CARD_CHECKS[sum % 11] == Character.toUpperCase(id.charAt(17));
    }

    private static boolean findBankCard(String text) {
        Matcher matcher = DIGIT_RUN_PATTERN.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && hasValidLuhn(digits)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValidLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static boolean findAddress(String text) {
        if (BUILDING_PATTERN.matcher(text).find()
                || STREET_PATTERN.matcher(text).find()
                || COMPOUND_PATTERN.matcher(text).find()) {
            return true;
        }
        if (matchesPlace(TOWN_PATTERN, text, 1) || matchesPlace(BARE_STREET_PATTERN, text, 2)) {
            return true;
        }
        Matcher matcher = DISTRICT_PATTERN.matcher(text);
        while (matcher.find()) {
            String match = matcher.group();
            String namePart = match.substring(0, match.length() - 1);
            if (!endsWithZoneName(namePart)) {
                return true;
            }
        }
        return false;
    }

    /** True when the pattern hits with a name part that is not a generic word. */
    private static boolean matchesPlace(Pattern pattern, String text, int minNameLength) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group();
            String namePart = match.substring(0, match.length() - suffixLength(match));
            if (namePart.length() >= minNameLength && !endsWithGenericPlace(namePart)) {
                return true;
            }
        }
        return false;
    }

    /** 街道/大道 are two characters; 镇/路 one. */
    private static int suffixLength(String match) {
        return match.endsWith("街道") || match.endsWith("大道") ? 2 : 1;
    }

    private static boolean endsWithGenericPlace(String namePart) {
        for (String suffix : GENERIC_PLACE_SUFFIXES) {
            if (namePart.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithZoneName(String namePart) {
        for (String suffix : ZONE_NAME_SUFFIXES) {
            if (namePart.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Fail closed: a 12+ digit run that is neither a validated card nor a timestamp. */
    private static boolean findUnexplainedDigitRun(String text) {
        Matcher matcher = DIGIT_RUN_PATTERN.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            boolean cardShaped = digits.length() >= 13 && digits.length() <= 19 && hasValidLuhn(digits);
            if (digits.length() >= 12 && !cardShaped && !TIMESTAMP_PREFIX.matcher(digits).find()) {
                return true;
            }
        }
        return false;
    }
}
